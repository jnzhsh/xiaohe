package app.xiaohe.tests;

import android.app.*;
import android.os.Bundle;
import org.json.JSONObject;

/** Old-APK runner deliberately references only APIs that existed before this feature. */
public final class ChatEditBaseline extends Instrumentation {
  private String sourceSha="unspecified";
  @Override public void onCreate(Bundle args){super.onCreate(args);if(args!=null)sourceSha=args.getString("sourceSha","unspecified");start();}
  @Override public void onStart(){Bundle result=new Bundle();try{JSONObject report=VoiceSupport.environment(this,sourceSha).put("testData","one synthetic note, no AI request").put("checks",ChatEditBaselineSmoke.run(this)).put("status","passed");ChatEditSupport.proof(this,"baseline",report);result.putString("status","passed");result.putString("measurements",report.toString());finish(Activity.RESULT_OK,result);}catch(Throwable error){result.putString("status","failed");result.putString("error",error.toString());finish(Activity.RESULT_CANCELED,result);}}
}
