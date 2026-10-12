using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;

public sealed class XiaoheClipboardBridge : IDisposable {
    private const int WH_KEYBOARD_LL = 13;
    private const int WM_KEYDOWN = 0x100, WM_KEYUP = 0x101, WM_SYSKEYDOWN = 0x104, WM_SYSKEYUP = 0x105;
    private const int VK_V = 0x56, VK_CONTROL = 0x11, VK_LCONTROL = 0xA2, VK_RCONTROL = 0xA3;
    private const int VK_MENU = 0x12, VK_LMENU = 0xA4, VK_RMENU = 0xA5, VK_LWIN = 0x5B, VK_RWIN = 0x5C;
    private readonly int targetPid;
    private readonly string statePath;
    private readonly Process target;
    private readonly IntPtr viewport;
    private readonly Process worker;
    private readonly StreamWriter input;
    private readonly BlockingCollection<PasteRequest> requests = new BlockingCollection<PasteRequest>(64);
    private readonly object stateGate = new object(), inputGate = new object();
    private readonly JavaScriptSerializer serializer = new JavaScriptSerializer { MaxJsonLength = Int32.MaxValue };
    private readonly HookProc callback;
    private readonly Thread clipboardThread;
    private System.Windows.Forms.Timer timer;
    private IntPtr hook;
    private volatile bool ready, stopping;
    private bool workerStarted;
    private bool ctrlLeft, ctrlRight, altLeft, altRight, winLeft, winRight, interceptedV;
    private long sequence, pasteCount, hookRefreshAt;
    private int hookGeneration;
    private string lastStatus = "starting";

    private struct PasteRequest { public long Id; public long Time; public uint ClipboardSequence; public IntPtr Window; public string BrowserRequestId; }
    [StructLayout(LayoutKind.Sequential)]
    private struct KeyData { public uint VkCode, ScanCode, Flags, Time; public UIntPtr ExtraInfo; }
    private delegate IntPtr HookProc(int code, IntPtr message, IntPtr data);
    [DllImport("user32.dll", SetLastError = true)] private static extern IntPtr SetWindowsHookEx(int id, HookProc proc, IntPtr module, uint thread);
    [DllImport("user32.dll")] private static extern bool UnhookWindowsHookEx(IntPtr handle);
    [DllImport("user32.dll")] private static extern IntPtr CallNextHookEx(IntPtr handle, int code, IntPtr message, IntPtr data);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] private static extern IntPtr GetModuleHandle(string module);
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr handle, out uint pid);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowText(IntPtr handle, StringBuilder text, int length);
    [DllImport("user32.dll")] private static extern IntPtr GetAncestor(IntPtr handle, uint flags);
    [DllImport("user32.dll")] private static extern IntPtr GetWindow(IntPtr handle, uint command);
    [DllImport("user32.dll")] private static extern short GetAsyncKeyState(int key);
    [DllImport("user32.dll")] private static extern uint GetClipboardSequenceNumber();
    [DllImport("user32.dll")] private static extern bool IsWindow(IntPtr window);
    [DllImport("user32.dll")] private static extern bool OpenClipboard(IntPtr owner);
    [DllImport("user32.dll")] private static extern bool CloseClipboard();
    [DllImport("user32.dll")] private static extern bool IsClipboardFormatAvailable(uint format);
    [DllImport("user32.dll")] private static extern IntPtr GetClipboardData(uint format);
    [DllImport("kernel32.dll")] private static extern IntPtr GlobalLock(IntPtr memory);
    [DllImport("kernel32.dll")] private static extern bool GlobalUnlock(IntPtr memory);
    [DllImport("kernel32.dll")] private static extern UIntPtr GlobalSize(IntPtr memory);

    private static long Now() { return (long)(DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds; }
    private static bool Down(int key) { return (GetAsyncKeyState(key) & 0x8000) != 0; }
    private static string Caption(IntPtr window) { var text = new StringBuilder(512); GetWindowText(window, text, text.Capacity); return text.ToString(); }
    private bool ForegroundIsTarget() {
        IntPtr window = GetForegroundWindow();
        uint pid;
        GetWindowThreadProcessId(window, out pid);
        // The hook must not synchronously ask a busy Qt window for captions.
        // Bind its viewport once; auxiliary dialogs have a different root HWND.
        return pid == targetPid && GetAncestor(window, 2) == viewport;
    }

    private XiaoheClipboardBridge(int pid, string node, string script, string state) {
        targetPid = pid;
        statePath = state;
        target = Process.GetProcessById(pid);
        if (!target.ProcessName.Equals("qemu-system-x86_64", StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException("Unexpected emulator process");
        viewport=target.MainWindowHandle;
        if(viewport==IntPtr.Zero||!Caption(viewport).Equals("Android Emulator - XiaoheExperience:5558",StringComparison.Ordinal))throw new InvalidOperationException("Experience viewport was not found");
        callback = OnKey;
        try {
        worker = new Process();
        worker.StartInfo = new ProcessStartInfo {
            FileName = node,
            Arguments = "\"" + script + "\"",
            WorkingDirectory = Path.GetDirectoryName(script),
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = new UTF8Encoding(false),
            StandardErrorEncoding = new UTF8Encoding(false)
        };
        worker.OutputDataReceived += OnWorkerStatus;
        worker.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs args) { if (args.Data != null) Status("worker_error"); };
        worker.Start();
        workerStarted = true;
        // Write UTF-8 bytes directly; Windows PowerShell's console encoding is not used.
        input = new StreamWriter(worker.StandardInput.BaseStream, new UTF8Encoding(false));
        input.AutoFlush = true;
        worker.BeginOutputReadLine();
        worker.BeginErrorReadLine();
        clipboardThread = new Thread(ReadClipboardRequests);
        clipboardThread.IsBackground = true;
        clipboardThread.SetApartmentState(ApartmentState.STA);
        clipboardThread.Start();
        ctrlLeft = Down(VK_LCONTROL); ctrlRight = Down(VK_RCONTROL);
        altLeft = Down(VK_LMENU); altRight = Down(VK_RMENU);
        winLeft = Down(VK_LWIN); winRight = Down(VK_RWIN);
        hook = SetWindowsHookEx(WH_KEYBOARD_LL, callback, GetModuleHandle(null), 0);
        if (hook == IntPtr.Zero) throw new InvalidOperationException("Keyboard hook could not be installed");
        hookRefreshAt=Now();hookGeneration=1;
        timer = new System.Windows.Forms.Timer();
        timer.Interval = 500;
        timer.Tick += delegate {
            if (stopping) return;
            try {
                if (target.HasExited || worker.HasExited || File.Exists(statePath + ".stop")) {Application.ExitThread();return;}
                if(interceptedV&&!Down(VK_LCONTROL)&&!Down(VK_RCONTROL)&&!Down(VK_V))interceptedV=false;
                if(!interceptedV&&Now()-hookRefreshAt>=15000){
                    // Windows can silently remove a timed-out hook. Renew it
                    // between key sequences so a long-running VM stays usable.
                    if(hook!=IntPtr.Zero)UnhookWindowsHookEx(hook);
                    hook=SetWindowsHookEx(WH_KEYBOARD_LL,callback,GetModuleHandle(null),0);
                    hookRefreshAt=Now();hookGeneration++;
                    ctrlLeft=Down(VK_LCONTROL);ctrlRight=Down(VK_RCONTROL);
                    altLeft=Down(VK_LMENU);altRight=Down(VK_RMENU);
                    winLeft=Down(VK_LWIN);winRight=Down(VK_RWIN);
                    Status(hook==IntPtr.Zero?"hook_error":ready?"ready":"reconnecting");
                }
            }
            catch { Application.ExitThread(); }
        };
        timer.Start();
        Status(ready ? "ready" : "starting");
        } catch { Dispose(); throw; }
    }

    private IntPtr OnKey(int code, IntPtr message, IntPtr data) {
        if (code < 0 || stopping) return CallNextHookEx(hook, code, message, data);
        int kind = message.ToInt32();
        bool down = kind == WM_KEYDOWN || kind == WM_SYSKEYDOWN;
        bool up = kind == WM_KEYUP || kind == WM_SYSKEYUP;
        if (!down && !up) return CallNextHookEx(hook, code, message, data);
        int key = (int)((KeyData)Marshal.PtrToStructure(data, typeof(KeyData))).VkCode;
        // Modifier state is tracked from messages; no keys or text are recorded.
        if (key == VK_CONTROL || key == VK_LCONTROL) ctrlLeft = down;
        if (key == VK_RCONTROL) ctrlRight = down;
        if (key == VK_MENU || key == VK_LMENU) altLeft = down;
        if (key == VK_RMENU) altRight = down;
        if (key == VK_LWIN) winLeft = down;
        if (key == VK_RWIN) winRight = down;
        if (key != VK_V) return CallNextHookEx(hook, code, message, data);
        if (interceptedV && !ForegroundIsTarget()) interceptedV = false;
        if (up && interceptedV) { interceptedV = false; return (IntPtr)1; }
        if (down && interceptedV) return (IntPtr)1;
        if (down && ready && (ctrlLeft || ctrlRight) && !(altLeft || altRight || winLeft || winRight) && ForegroundIsTarget()) {
            PasteRequest request = new PasteRequest { Id = Interlocked.Increment(ref sequence), Time = Now(), ClipboardSequence = GetClipboardSequenceNumber(), Window = GetForegroundWindow() };
            // The hook immediately hands work to a separate STA clipboard thread.
            if (requests.TryAdd(request)) { interceptedV = true; return (IntPtr)1; }
        }
        return CallNextHookEx(hook, code, message, data);
    }

    private void ReadClipboardRequests() {
        foreach (PasteRequest request in requests.GetConsumingEnumerable()) {
            if (stopping) break;
            try {
                if (!RequestStillValid(request)) { Status(RequestInvalidReason(request)); continue; }
                string clipboardReason;
                string text = ReadUnicodeClipboard(request, out clipboardReason);
                if (text == null) { Status(clipboardReason); continue; }
                if (!FocusStillValid(request)) { text = null; Status(RequestInvalidReason(request)); continue; }
                if (text.Length == 0) { Status("no_text"); continue; }
                var fields=new Dictionary<string,object>{{"id",request.Id},{"time",request.Time},{"text",text}};
                if(!String.IsNullOrEmpty(request.BrowserRequestId))fields.Add("requestId",request.BrowserRequestId);
                string payload = serializer.Serialize(fields);
                lock (inputGate) { if (!stopping && FocusStillValid(request)) input.WriteLine(payload); }
                // Clipboard contents are never sent to arguments, files, or status output.
                text = null;
                payload = null;
            } catch { Status("clipboard_error"); }
        }
    }

    private bool RequestStillValid(PasteRequest request) {
        return FocusStillValid(request) && GetClipboardSequenceNumber() == request.ClipboardSequence;
    }

    private bool FocusStillValid(PasteRequest request) {
        long age = Now() - request.Time;
        uint pid;
        if (stopping || age < 0 || age > 3000 || !IsWindow(request.Window)) return false;
        GetWindowThreadProcessId(request.Window, out pid);
        // The key press already bound the destination. A later Windows focus
        // change must not retarget it; the browser separately checks the
        // original element identity, selection and visibility atomically.
        return pid == targetPid && Caption(GetAncestor(request.Window, 2)).Equals("Android Emulator - XiaoheExperience:5558", StringComparison.Ordinal);
    }

    private string RequestInvalidReason(PasteRequest request) {
        long age = Now() - request.Time;
        if (age < 0 || age > 3000) return "request_expired";
        if (!FocusStillValid(request)) return "window_changed";
        if (GetClipboardSequenceNumber() != request.ClipboardSequence) return "clipboard_changed";
        return "request_changed";
    }

    private string ReadUnicodeClipboard(PasteRequest request, out string reason) {
        // Native Unicode text avoids OLE clipboard message-pump dependencies.
        reason = "request_changed";
        bool opened = false;
        for (int attempt = 0; attempt < 8 && RequestStillValid(request); attempt++) {
            if (OpenClipboard(IntPtr.Zero)) { opened = true; break; }
            Thread.Sleep(20);
        }
        if (!opened) { reason = RequestStillValid(request) ? "clipboard_busy" : RequestInvalidReason(request); return null; }
        try {
            if (!RequestStillValid(request)) { reason = RequestInvalidReason(request); return null; }
            if (!IsClipboardFormatAvailable(13)) { reason = "unicode_unavailable"; return null; }
            IntPtr memory = GetClipboardData(13);
            if (memory == IntPtr.Zero) { reason = "unicode_missing"; return null; }
            ulong size = GlobalSize(memory).ToUInt64();
            if (size < 2 || size / 2 > Int32.MaxValue) { reason = "unicode_size"; return null; }
            IntPtr pointer = GlobalLock(memory);
            if (pointer == IntPtr.Zero) { reason = "unicode_lock"; return null; }
            try {
                // This snapshot belongs to the key press: while open, no other
                // process can replace the clipboard. Delayed Unicode rendering
                // may itself advance its sequence number without a new copy.
                string text = Marshal.PtrToStringUni(pointer, (int)(size / 2));
                int terminator = text.IndexOf('\0');
                return terminator >= 0 ? text.Substring(0, terminator) : text;
            } finally { GlobalUnlock(memory); }
        } finally { CloseClipboard(); }
    }

    private void OnWorkerStatus(object sender, DataReceivedEventArgs args) {
        if (args.Data == null || stopping) return;
        try {
            Dictionary<string, object> data = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(args.Data);
            object value;
            if (!data.TryGetValue("event", out value)) return;
            string name = value as string;
            if (name == "ready") { ready = true; Status("ready"); }
            else if(name=="disconnected"){ready=false;Status("reconnecting");}
            else if(name=="request_paste"){
                string requestId=data.ContainsKey("requestId")?data["requestId"] as string:null;
                if(!ready||String.IsNullOrEmpty(requestId)||requestId.Length>128||!System.Text.RegularExpressions.Regex.IsMatch(requestId,"^[A-Za-z0-9_-]+$")||!ForegroundIsTarget())return;
                var request=new PasteRequest{Id=Interlocked.Increment(ref sequence),Time=Now(),ClipboardSequence=GetClipboardSequenceNumber(),Window=GetForegroundWindow(),BrowserRequestId=requestId};
                if(!requests.TryAdd(request))Status("queue_full");
            }
            else if (name == "pasted") { Interlocked.Increment(ref pasteCount); Status("pasted"); }
            else if (name == "skipped" || name == "error") {
                string reason = data.ContainsKey("reason") ? data["reason"] as string : "";
                HashSet<string> safe = new HashSet<string> {
                    "invalid_request","empty_text","text_too_large","queue_full","stale_request","request_expired",
                    "device_unavailable","app_not_running","app_not_foreground","forward_in_use",
                    "connection_failed","target_unavailable","wrong_page","no_focus_history",
                    "focus_changed","selection_changed","target_hidden","unsupported_target",
                    "insertion_unverified","paste_cancelled","connection_recovering","worker_stopping","internal_error"
                };
                Status((name == "skipped" ? "skipped_" : "error_") + (safe.Contains(reason) ? reason : "other"));
            }
        } catch { Status("worker_error"); }
    }

    private void Status(string status) {
        lock (stateGate) {
            lastStatus = status;
            try {
                string json = new JavaScriptSerializer().Serialize(new Dictionary<string, object> {
                    {"bridgePid", Process.GetCurrentProcess().Id}, {"emulatorPid", targetPid},
                    {"ready", ready && hook != IntPtr.Zero && !stopping}, {"pasteCount", Interlocked.Read(ref pasteCount)},
                    {"status", lastStatus}, {"updated", Now()}
                    ,{"hookGeneration",hookGeneration}
                });
                string temporary = statePath + ".tmp";
                File.WriteAllText(temporary, json, new UTF8Encoding(false));
                if (File.Exists(statePath)) File.Replace(temporary, statePath, null);
                else File.Move(temporary, statePath);
            } catch { }
        }
    }

    public void Dispose() {
        if (stopping) return;
        stopping = true;
        ready = false;
        if (hook != IntPtr.Zero) { UnhookWindowsHookEx(hook); hook = IntPtr.Zero; }
        if (timer != null) { timer.Stop(); timer.Dispose(); }
        requests.CompleteAdding();
        // A stalled pipe must never keep the keyboard hook's process alive.
        bool ownsInput = input != null && Monitor.TryEnter(inputGate, 100);
        if (ownsInput) {
            try { input.Close(); } catch { }
            finally { Monitor.Exit(inputGate); }
        } else { try { if (workerStarted && !worker.HasExited) worker.Kill(); } catch { } }
        try { if (workerStarted && !worker.WaitForExit(7500)) { worker.Kill(); worker.WaitForExit(1000); } } catch { }
        try { if (clipboardThread != null && clipboardThread.IsAlive) clipboardThread.Join(1000); } catch { }
        if (worker != null) worker.Dispose();
        if (target != null) target.Dispose();
        Status("stopped");
        GC.KeepAlive(callback);
    }

    public static void Run(int pid, string node, string script, string state) {
        if (Thread.CurrentThread.GetApartmentState() != ApartmentState.STA) throw new InvalidOperationException("Clipboard helper requires an STA message loop");
        using (Mutex mutex = new Mutex(false, "Local\\Xiaohe.Experience.Clipboard.5558")) {
            bool acquired = false;
            try {
                try { acquired = mutex.WaitOne(0); } catch (AbandonedMutexException) { acquired = true; }
                if (!acquired) return;
                if (File.Exists(state + ".stop")) File.Delete(state + ".stop");
                using (XiaoheClipboardBridge bridge = new XiaoheClipboardBridge(pid, node, script, state)) {
                    Application.Run();
                }
            } finally { if (acquired) mutex.ReleaseMutex(); }
        }
    }
}
