package app.xiaohe.tests;

import android.app.*;
import android.os.Bundle;
import org.json.JSONObject;

public final class FeedbackTest extends Instrumentation {
  private String mode="native",sourceSha="unspecified";
  @Override public void onCreate(Bundle arguments){super.onCreate(arguments);if(arguments!=null){mode=arguments.getString("mode","native");sourceSha=arguments.getString("sourceSha","unspecified");}start();}
  @Override public void onStart(){Bundle result=new Bundle();JSONObject report=null;try{
    report=VoiceSupport.environment(this,sourceSha).put("testData","synthetic feedback drafts, mock GitHub responses, fixture credentials; no real issues or personal content");
    if(mode.equals("native"))report.put("checks",FeedbackSmoke.run(this));
    else if(mode.equals("ui"))report.put("checks",FeedbackUiSmoke.run(this));
    else if(mode.equals("transport"))report.put("checks",FeedbackTransportSmoke.run(this));
    else if(mode.equals("investigation"))report.put("checks",FeedbackInvestigationSmoke.run(this));
    else throw new IllegalArgumentException("Unknown feedback test mode");
    report.put("status","passed");FeedbackSupport.proof(this,mode,report);result.putString("status","passed");result.putString("verified",mode);result.putString("measurements",report.toString());finish(Activity.RESULT_OK,result);
  }catch(Throwable error){try{if(report!=null){report.put("status","failed").put("error",error.toString());FeedbackSupport.proof(this,mode,report);}}catch(Exception ignored){}result.putString("status","failed");result.putString("error",error.toString());finish(Activity.RESULT_CANCELED,result);}}
}
