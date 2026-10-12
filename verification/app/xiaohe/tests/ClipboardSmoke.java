package app.xiaohe.tests;

import android.app.*;import android.content.*;import android.os.SystemClock;import android.view.*;import android.view.inputmethod.*;import android.webkit.WebView;import org.json.*;
import java.lang.reflect.Field;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicReference;

/** Native clipboard and real IME composition, on the isolated test device only. */
public final class ClipboardSmoke {
  private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  static String evaluate(Instrumentation test,WebView web,String script)throws Exception{
    CountDownLatch latch=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>();
    test.runOnMainSync(()->web.evaluateJavascript(script,result->{value.set(result);latch.countDown();}));
    check(latch.await(8,TimeUnit.SECONDS),"WebView callback timed out");return value.get();
  }
  static void tap(Instrumentation test,WebView web,String id)throws Exception{
    JSONObject p=new JSONObject(evaluate(test,web,"(()=>{const r=document.getElementById("+JSONObject.quote(id)+").getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2,w:innerWidth};})()"));
    final float[] point=new float[2];test.runOnMainSync(()->{int[] origin=new int[2];web.getLocationOnScreen(origin);double scale=web.getWidth()/p.optDouble("w");point[0]=(float)(origin[0]+p.optDouble("x")*scale);point[1]=(float)(origin[1]+p.optDouble("y")*scale);});
    long now=SystemClock.uptimeMillis();test.sendPointerSync(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,point[0],point[1],0));test.sendPointerSync(MotionEvent.obtain(now,now+40,MotionEvent.ACTION_UP,point[0],point[1],0));
  }
  public static void run(Instrumentation test)throws Exception{
    Activity activity=test.startActivitySync(new Intent(test.getTargetContext(),app.xiaohe.assistant.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Field field=activity.getClass().getDeclaredField("web");field.setAccessible(true);WebView web=(WebView)field.get(activity);
    try{
      boolean loaded=false;for(int i=0;i<60;i++){if("true".equals(evaluate(test,web,"!!document.querySelector('button[data-paste=cfg-key]')"))){loaded=true;break;}Thread.sleep(100);}check(loaded,"Clipboard controls loaded");
      check("true".equals(evaluate(test,web,"typeof window.__xiaohePasteFromHost==='undefined'")),"Native phone path must run without desktop helper");
      String sample="DEMO_NATIVE_CLIP_ONLY_aB9-\"'$()+/中文";
      test.runOnMainSync(()->((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("fixture",sample)));
      evaluate(test,web,"(()=>{showSettings();const e=document.getElementById('cfg-key');e.value='old';e.focus();e.select();const b=document.querySelector('button[data-paste=cfg-key]');b.id='native-paste-test';b.scrollIntoView({block:'center'});return true;})()");
      test.runOnMainSync(()->{InputConnection input=web.onCreateInputConnection(new EditorInfo());if(input!=null)input.setComposingText("unfinished",1);});
      Thread.sleep(250);
      evaluate(test,web,"(()=>{window.__clipTestClicked=false;document.addEventListener('click',e=>{if(e.target.closest('button[data-paste=cfg-key]'))window.__clipTestClicked=e.isTrusted;},true);document.getElementById('native-paste-test').scrollIntoView({block:'center'});return true;})()");
      Thread.sleep(250);tap(test,web,"native-paste-test");
      boolean inserted=false;for(int i=0;i<50;i++){if("true".equals(evaluate(test,web,"document.getElementById('cfg-key').value==="+JSONObject.quote(sample)))){inserted=true;break;}Thread.sleep(100);}
      if(!inserted){String flags=evaluate(test,web,"(()=>{const b=document.getElementById('native-paste-test'),r=b.getBoundingClientRect(),t=document.getElementById('toast');return {clicked:!!window.__clipTestClicked,disabled:b.disabled,buttonInView:r.top>=0&&r.bottom<=innerHeight,noClipboard:t.textContent.includes('剪贴板'),nativeMissing:t.textContent.includes('不支持'),positionChanged:t.textContent.includes('位置变'),pasteRejected:t.textContent.includes('粘贴成功'),focused:document.activeElement.id==='cfg-key'};})()");throw new AssertionError("Native composing paste failed: "+flags);}
      evaluate(test,web,"(()=>{document.getElementById('cfg-key').blur();document.getElementById('cfg-model').focus();document.getElementById('cfg-key').focus();return true;})()");
      Thread.sleep(1100);check("true".equals(evaluate(test,web,"document.getElementById('cfg-key').value==="+JSONObject.quote(sample))),"Clipboard content survives input-method restart and focus changes");
      check("true".equals(evaluate(test,web,"document.getElementById('cfg-key').type==='password'")),"Key stays masked");
      evaluate(test,web,"(()=>{document.getElementById('cfg-key').value='';closeSheet('settings-dialog');return true;})()");
    }finally{test.runOnMainSync(activity::finish);}
  }
}
