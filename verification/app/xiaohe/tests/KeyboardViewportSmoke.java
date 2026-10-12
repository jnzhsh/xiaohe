package app.xiaohe.tests;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/** Exercise real IME visibility and editor hit targets, without sending a message or saving a key. */
public final class KeyboardViewportSmoke {
  private static final String DRAFT="键盘测试草稿\n第二行也要保留\n第三行可在输入框里滚动";
  private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  private static JSONObject nativeGeometry(Instrumentation test,Activity activity,WebView web)throws Exception{
    AtomicReference<JSONObject> result=new AtomicReference<>();
    test.runOnMainSync(()->{try{
      View decor=activity.getWindow().getDecorView();int[] origin=new int[2],window=new int[2];web.getLocationOnScreen(origin);decor.getLocationOnScreen(window);
      int bottom=window[1]+decor.getHeight(),ime=0;boolean shown=false;
      if(Build.VERSION.SDK_INT>=30){WindowInsets insets=decor.getRootWindowInsets();if(insets!=null){ime=insets.getInsets(WindowInsets.Type.ime()).bottom;shown=insets.isVisible(WindowInsets.Type.ime())&&ime>0;}}
      else{Rect visible=new Rect();decor.getWindowVisibleDisplayFrame(visible);DisplayMetrics display=new DisplayMetrics();activity.getWindowManager().getDefaultDisplay().getRealMetrics(display);shown=visible.bottom<display.heightPixels-100;bottom=visible.bottom;}
      result.set(new JSONObject().put("webTop",origin[1]).put("webHeight",web.getHeight()).put("webWidth",web.getWidth()).put("windowBottom",bottom).put("imeHeight",ime).put("imeShown",shown).put("safeBottom",bottom-ime));
    }catch(Exception error){throw new RuntimeException(error);}});return result.get();
  }
  private static JSONObject geometry(Instrumentation test,Activity activity,WebView web,String field)throws Exception{
    JSONObject dom=new JSONObject(ClipboardSmoke.evaluate(test,web,"(()=>{const rect=id=>{const r=document.getElementById(id).getBoundingClientRect();return {top:r.top,bottom:r.bottom};};return {innerWidth,innerHeight,visualHeight:window.visualViewport?visualViewport.height:innerHeight,input:rect("+JSONObject.quote(field)+"),send:rect('send')};})()"));
    JSONObject nativeView=nativeGeometry(test,activity,web);double scale=nativeView.getDouble("webWidth")/dom.getDouble("innerWidth");
    return new JSONObject().put("dom",dom).put("native",nativeView).put("inputScreenBottom",nativeView.getDouble("webTop")+dom.getJSONObject("input").getDouble("bottom")*scale).put("sendScreenBottom",nativeView.getDouble("webTop")+dom.getJSONObject("send").getDouble("bottom")*scale);
  }
  private static void waitKeyboard(Instrumentation test,Activity activity,WebView web,boolean visible)throws Exception{
    for(int attempt=0;attempt<60;attempt++){if(nativeGeometry(test,activity,web).getBoolean("imeShown")==visible){Thread.sleep(400);return;}Thread.sleep(100);}throw new AssertionError("Real software keyboard did not become "+visible);
  }
  private static void keyboard(Instrumentation test,Activity activity,WebView web,boolean visible)throws Exception{
    test.runOnMainSync(()->{InputMethodManager manager=(InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);if(visible)manager.showSoftInput(web,InputMethodManager.SHOW_IMPLICIT);else manager.hideSoftInputFromWindow(web.getWindowToken(),0);});waitKeyboard(test,activity,web,visible);
  }
  private static void saveEvidence(Instrumentation test,Activity activity,String phase,String stage,JSONObject value)throws Exception{
    File folder=new File(activity.getExternalFilesDir(null),"keyboard-proof");check(folder.exists()||folder.mkdirs(),"Evidence directory available");
    try(Writer writer=new OutputStreamWriter(new FileOutputStream(new File(folder,phase+"-"+stage+".json")),"UTF-8")){writer.write(value.toString(2));}
    Bitmap screenshot=test.getUiAutomation().takeScreenshot();check(screenshot!=null,"Screenshot available");try(OutputStream out=new FileOutputStream(new File(folder,phase+"-"+stage+".png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}finally{screenshot.recycle();}
  }
  public static JSONObject run(Instrumentation test,String phase)throws Exception{
    Activity activity=test.startActivitySync(new Intent(test.getTargetContext(),app.xiaohe.assistant.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Field member=activity.getClass().getDeclaredField("web");member.setAccessible(true);WebView web=(WebView)member.get(activity);JSONObject stages=new JSONObject();
    try{
      boolean ready=false;for(int i=0;i<60;i++){if("true".equals(ClipboardSmoke.evaluate(test,web,"!!window.App&&!!document.getElementById('message-input')"))){ready=true;break;}Thread.sleep(100);}check(ready,"Test page loaded");
      ClipboardSmoke.evaluate(test,web,"(()=>{document.querySelectorAll('dialog[open]').forEach(d=>d.close());window.__keyboardOriginalDraft=document.getElementById('message-input').value;document.getElementById('message-input').value="+JSONObject.quote(DRAFT)+";saveDraft();return true;})()");
      keyboard(test,activity,web,false);JSONObject closed=geometry(test,activity,web,"message-input");stages.put("closed",closed);saveEvidence(test,activity,phase,"closed",closed);
      ClipboardSmoke.tap(test,web,"message-input");keyboard(test,activity,web,true);JSONObject chat=geometry(test,activity,web,"message-input");stages.put("chat",chat);saveEvidence(test,activity,phase,"chat",chat);
      double safe=chat.getJSONObject("native").getDouble("safeBottom");
      check(chat.getDouble("inputScreenBottom")<=safe+4&&chat.getDouble("sendScreenBottom")<=safe+4,"Chat input and send button must be above actual IME: "+chat);
      check(chat.getJSONObject("dom").getDouble("innerHeight")<closed.getJSONObject("dom").getDouble("innerHeight")-60,"Keyboard must reduce available page height");
      ClipboardSmoke.evaluate(test,web,"(()=>{window.__keyboardSendClicked=false;window.__keyboardSendBlock=e=>{if(e.target.closest('#send')){window.__keyboardSendClicked=e.isTrusted;e.preventDefault();e.stopImmediatePropagation();}};document.addEventListener('click',window.__keyboardSendBlock,true);return true;})()");
      ClipboardSmoke.tap(test,web,"send");Thread.sleep(200);check("true".equals(ClipboardSmoke.evaluate(test,web,"window.__keyboardSendClicked")),"Visible send button receives a trusted touch");
      ClipboardSmoke.evaluate(test,web,"(()=>{document.removeEventListener('click',window.__keyboardSendBlock,true);return true;})()");
      for(int cycle=0;cycle<3;cycle++){
        keyboard(test,activity,web,false);ClipboardSmoke.tap(test,web,"message-input");keyboard(test,activity,web,true);JSONObject repeated=geometry(test,activity,web,"message-input");
        check(repeated.getDouble("sendScreenBottom")<=repeated.getJSONObject("native").getDouble("safeBottom")+4,"Repeated IME does not obscure sending");
        check("true".equals(ClipboardSmoke.evaluate(test,web,"document.getElementById('message-input').value==="+JSONObject.quote(DRAFT))),"Multiline draft survives repeated IME changes");
      }
      keyboard(test,activity,web,false);JSONObject restored=geometry(test,activity,web,"message-input");stages.put("restored",restored);check(Math.abs(restored.getJSONObject("dom").getDouble("innerHeight")-closed.getJSONObject("dom").getDouble("innerHeight"))<=2,"Closing keyboard restores page height without blank space");
      check("true".equals(ClipboardSmoke.evaluate(test,web,"document.querySelector('.bottom-nav').getBoundingClientRect().height>0")),"Closing keyboard keeps page navigation available in both orientations");
      ClipboardSmoke.evaluate(test,web,"(()=>{showSettings();const key=document.getElementById('cfg-key');key.value='FAKE_KEY_FOR_KEYBOARD_TEST';key.scrollIntoView({block:'center'});return true;})()");Thread.sleep(150);
      ClipboardSmoke.tap(test,web,"cfg-key");keyboard(test,activity,web,true);JSONObject settings=geometry(test,activity,web,"cfg-key");stages.put("settings",settings);saveEvidence(test,activity,phase,"settings",settings);
      check(settings.getDouble("inputScreenBottom")<=settings.getJSONObject("native").getDouble("safeBottom")+4,"Focused settings input remains above IME: "+settings);
      check("true".equals(ClipboardSmoke.evaluate(test,web,"(()=>{const f=document.getElementById('cfg-key'),b=document.querySelector('[data-close=\"settings-dialog\"]'),r=b.getBoundingClientRect();return f.type==='password'&&f.value==='FAKE_KEY_FOR_KEYBOARD_TEST'&&r.top>=0&&r.bottom<=Math.min(innerHeight,visualViewport?visualViewport.height:innerHeight);})()")),"Key is masked and settings close control stays visible");
      return stages;
    }finally{
      try{ClipboardSmoke.evaluate(test,web,"(()=>{document.removeEventListener('click',window.__keyboardSendBlock,true);document.getElementById('cfg-key').value='';document.querySelectorAll('dialog[open]').forEach(d=>d.close());if(window.__keyboardOriginalDraft!==undefined){document.getElementById('message-input').value=window.__keyboardOriginalDraft;saveDraft();}return true;})()");keyboard(test,activity,web,false);}catch(Exception ignored){}
      test.runOnMainSync(activity::finish);
    }
  }
}
