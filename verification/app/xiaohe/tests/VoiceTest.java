package app.xiaohe.tests;

import android.app.*;
import android.os.Bundle;
import org.json.JSONObject;

public final class VoiceTest extends Instrumentation {
  private String mode="decoder",sourceSha="unspecified";
  @Override public void onCreate(Bundle arguments){super.onCreate(arguments);if(arguments!=null){mode=arguments.getString("mode","decoder");sourceSha=arguments.getString("sourceSha","unspecified");}start();}
  @Override public void onStart(){Bundle results=new Bundle();JSONObject report=null;try{
    report=VoiceSupport.environment(this,sourceSha);
    if(mode.equals("decoder"))report.put("checks",OfflineDecoderSmoke.run(this));
    else if(mode.equals("controller"))report.put("checks",OfflineControllerSmoke.run(this));
    else if(mode.equals("microphone"))report.put("checks",OfflineControllerSmoke.microphone(this));
    else if(mode.equals("ui"))report.put("checks",OfflineVoiceUiSmoke.run(this));
    else throw new IllegalArgumentException("Unknown voice test mode");
    report.put("status","passed");VoiceSupport.proof(this,mode,report);results.putString("status","passed");results.putString("verified",mode);results.putString("measurements",report.toString());finish(Activity.RESULT_OK,results);
  }catch(Throwable failure){try{if(report!=null){report.put("status","failed").put("error",failure.toString());VoiceSupport.proof(this,mode,report);}}catch(Exception ignored){}results.putString("status","failed");results.putString("error",failure.toString());finish(Activity.RESULT_CANCELED,results);}}
}
