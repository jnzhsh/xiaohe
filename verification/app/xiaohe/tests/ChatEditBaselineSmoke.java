package app.xiaohe.tests;

import android.app.*;
import android.content.Intent;
import android.webkit.WebView;
import app.xiaohe.assistant.*;
import org.json.*;
import java.lang.reflect.Field;

/** Observes the old manual textarea behavior with real pointer events, without a future-class reference. */
public final class ChatEditBaselineSmoke {
  public static JSONObject run(Instrumentation test)throws Exception{
    Activity activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));WebView web=VoiceSupport.web(activity);Field storeField=MainActivity.class.getDeclaredField("store");storeField.setAccessible(true);Object previous=storeField.get(activity);
    try{
      ChatEditSupport.wait(test,web,"typeof state!=='undefined'&&state.draftRestored&&pendingCalls.size===0","Baseline initial native bootstrap completed");
      try(ChatEditSupport.Fixture fixture=new ChatEditSupport.Fixture(test)){
        storeField.set(activity,fixture.scope.db);JSONObject record=ChatEditSupport.memory(fixture.scope.db,"合成旧版编辑条目","这是保留的合成原话。","note","note");String before=fixture.scope.db.one("memories",record.getString("id")).toString();int messages=fixture.scope.db.all("messages").length();
        ChatEditSupport.js(test,web,"loadData("+fixture.scope.db.bootstrap()+");true");ChatEditSupport.tap(test,web,"button[data-view='memory']");String selector="button[data-memory=\""+record.getString("id")+"\"]";ChatEditSupport.wait(test,web,"!!document.querySelector("+JSONObject.quote(selector)+")","Baseline synthetic record appears");ChatEditSupport.tap(test,web,selector);ChatEditSupport.wait(test,web,"document.getElementById('detail-dialog').open&&!!document.querySelector('[data-edit-memory]')","Baseline manual edit entry is present");ChatEditSupport.tap(test,web,"button[data-edit-memory]");ChatEditSupport.wait(test,web,"document.getElementById('detail-dialog').open&&!!document.getElementById('memory-editor')","Baseline edit opens a manual textarea");ChatEditSupport.check("true".equals(ChatEditSupport.js(test,web,"state.view==='memory'&&document.getElementById('memory-editor').value==="+JSONObject.quote(record.getString("content")))),"The old entry keeps the user in memory detail and pre-fills the entire original for manual rewriting");ChatEditSupport.screenshot(test,web,"baseline-manual-memory-editor");ChatEditSupport.check(fixture.scope.db.one("memories",record.getString("id")).toString().equals(before)&&fixture.scope.db.all("messages").length()==messages,"Opening the old manual editor does not write a record or send a message");
        return new JSONObject().put("priorBehaviorObserved","memory detail opens a manual textarea with the existing content").put("manualTextarea",true).put("staysInMemoryView",true).put("writesBeforeSave",0).put("aiRequests",0).put("supportsNewChatAttachment","not present in this observed old edit flow");
      }
    }finally{storeField.set(activity,previous);test.runOnMainSync(activity::finish);}
  }
}
