package app.xiaohe.tests;

import android.app.*;
import android.os.Bundle;
import org.json.*;

/** Baseline branch only loads classes available in dev/1.3; no new production type is referenced. */
public final class MemoryAnswerTest extends Instrumentation {
  private String mode="native",sourceSha="unspecified";
  @Override public void onCreate(Bundle args){super.onCreate(args);if(args!=null){mode=args.getString("mode","native");sourceSha=args.getString("sourceSha","unspecified");}start();}
  @Override public void onStart(){Bundle result=new Bundle();JSONObject report=null;try{
    report=VoiceSupport.environment(this,sourceSha).put("testData","synthetic people, gifts and recipes; unauthenticated loopback HTTP; isolated SQLite; no private backup or model");
    if(mode.equals("baseline"))report.put("checks",MemoryAnswerBaselineSmoke.run(this));
    else if(mode.equals("native")||mode.equals("context"))report.put("checks",MemoryAnswerNativeSmoke.run(this));
    else if(mode.equals("ui"))report.put("checks",MemoryAnswerUiSmoke.run(this));
    else throw new IllegalArgumentException("Unknown memory answer test mode");
    report.put("status","passed");MemoryAnswerSupport.proof(this,mode,report);result.putString("status","passed");result.putString("verified",mode);result.putString("measurements",report.toString());finish(Activity.RESULT_OK,result);
  }catch(Throwable failure){try{if(report!=null){report.put("status","failed").put("lastScenario",MemoryAnswerSupport.lastScenario).put("error",failure.toString()).put("lastObservation",MemoryAnswerSupport.lastObservation);MemoryAnswerSupport.proof(this,mode,report);}}catch(Exception ignored){}result.putString("status","failed");result.putString("lastScenario",MemoryAnswerSupport.lastScenario);result.putString("error",failure.toString());if(MemoryAnswerSupport.lastObservation!=null)result.putString("lastObservation",MemoryAnswerSupport.lastObservation.toString());finish(Activity.RESULT_CANCELED,result);}}
}
