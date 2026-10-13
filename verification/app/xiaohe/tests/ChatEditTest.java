package app.xiaohe.tests;

import android.app.*;
import android.os.Bundle;
import org.json.JSONObject;

public final class ChatEditTest extends Instrumentation {
  private String mode="native",sourceSha="unspecified";
  @Override public void onCreate(Bundle arguments){super.onCreate(arguments);if(arguments!=null){mode=arguments.getString("mode","native");sourceSha=arguments.getString("sourceSha","unspecified");}start();}
  @Override public void onStart(){Bundle result=new Bundle();JSONObject report=null;try{
    report=VoiceSupport.environment(this,sourceSha).put("testData","synthetic local records and a local HTTP model fixture; no user content or real model calls");
    if(mode.equals("native"))report.put("checks",ChatEditNativeSmoke.run(this));
    else if(mode.equals("ui"))report.put("checks",ChatEditUiSmoke.run(this));
    else if(mode.equals("keyboard"))report.put("checks",ChatEditKeyboardSmoke.run(this));
    else if(mode.equals("baseline"))report.put("checks",ChatEditBaselineSmoke.run(this));
    else throw new IllegalArgumentException("Unknown chat-edit test mode");
    report.put("status","passed");ChatEditSupport.proof(this,mode,report);result.putString("status","passed");result.putString("verified",mode);result.putString("measurements",report.toString());finish(Activity.RESULT_OK,result);
  }catch(Throwable error){try{if(report!=null){report.put("status","failed").put("error",error.toString());if(mode.equals("native"))report.put("lastScenario",ChatEditNativeSmoke.lastScenario);ChatEditSupport.proof(this,mode,report);}}catch(Exception ignored){}result.putString("status","failed");result.putString("error",error.toString());if(mode.equals("native"))result.putString("lastScenario",ChatEditNativeSmoke.lastScenario);finish(Activity.RESULT_CANCELED,result);}}
}
