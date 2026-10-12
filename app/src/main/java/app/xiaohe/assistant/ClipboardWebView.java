package app.xiaohe.assistant;

import android.content.Context;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;

/** Keep clipboard edits in sync with the input method's composing session. */
final class ClipboardWebView extends WebView {
  private InputConnection input;
  ClipboardWebView(Context context) { super(context); }
  @Override public InputConnection onCreateInputConnection(EditorInfo info) {
    input=super.onCreateInputConnection(info);
    return input;
  }
  void finishPasteComposition() {
    if(input!=null) { try { input.finishComposingText(); } catch(RuntimeException ignored) {} }
    InputMethodManager manager=(InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    if(manager!=null)manager.restartInput(this);
  }
}
