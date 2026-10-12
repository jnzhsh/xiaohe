package app.xiaohe.tests;
import android.app.*;import android.content.Intent;import android.webkit.WebView;import java.lang.reflect.Field;

public final class OrganizerUiSmoke {
  public static void run(Instrumentation test)throws Exception{
    Activity activity=test.startActivitySync(new Intent(test.getTargetContext(),app.xiaohe.assistant.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Field member=activity.getClass().getDeclaredField("web");member.setAccessible(true);WebView web=(WebView)member.get(activity);
    try{
      boolean loaded=false;for(int i=0;i<60;i++){if("true".equals(ClipboardSmoke.evaluate(test,web,"!!document.getElementById('date-clues')&&!!state.messages"))){loaded=true;break;}Thread.sleep(100);}if(!loaded)throw new AssertionError("Organizer UI loaded");
      ClipboardSmoke.evaluate(test,web,"(()=>{showSettings();const b=document.getElementById('archive-now');b.scrollIntoView({block:'center'});window.__organizerClick=false;window.__organizerProgress=false;document.addEventListener('click',e=>{if(e.target.closest('#archive-now')){window.__organizerClick=e.isTrusted;setTimeout(()=>window.__organizerProgress=b.disabled&&b.textContent.includes('正在整理'),0);}},true);return true;})()");
      Thread.sleep(300);ClipboardSmoke.tap(test,web,"archive-now");
      boolean finished=false;for(int i=0;i<100;i++){String passed=ClipboardSmoke.evaluate(test,web,"(()=>{const b=document.getElementById('archive-now'),r=document.getElementById('archive-report');return !!window.__organizerClick&&!!window.__organizerProgress&&!b.disabled&&(r.textContent.includes('整理完成')||r.textContent.includes('已经检查'))&&!!document.getElementById('toast');})()");if("true".equals(passed)){finished=true;break;}Thread.sleep(100);}if(!finished)throw new AssertionError("Organizer UI: "+ClipboardSmoke.evaluate(test,web,"(()=>{const b=document.getElementById('archive-now'),r=document.getElementById('archive-report');return {clicked:!!window.__organizerClick,progress:!!window.__organizerProgress,disabled:b.disabled,report:!!r.textContent.includes('整理完成')||!!r.textContent.includes('已经检查'),error:r.textContent.startsWith('这次没能')?r.textContent:'',toast:!!document.getElementById('toast')};})()"));
    }finally{test.runOnMainSync(activity::finish);}
  }
}
