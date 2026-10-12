param([Parameter(Mandatory = $true)][int]$EmulatorProcessId)
$ErrorActionPreference = 'Stop'
$taskWork = 'C:/Users/sino/Documents/Codex/2026-10-09/xai/work'
$taskNode = 'C:/Users/sino/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe'
$taskSource = Join-Path $taskWork 'XiaoheClipboardBridge.cs'
$taskWorker = Join-Path $taskWork 'experience_clipboard_worker.cjs'
$taskState = Join-Path $taskWork 'android-tools/experience/clipboard-bridge-state.json'
Add-Type -Path $taskSource -ReferencedAssemblies 'System.dll','System.Core.dll','System.Windows.Forms.dll','System.Web.Extensions.dll'
[XiaoheClipboardBridge]::Run($EmulatorProcessId, $taskNode, $taskWorker, $taskState)
