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
    else if(mode.equals("dates"))try(MemoryAnswerSupport.Server server=new MemoryAnswerSupport.Server()){
      server.responder=request->{JSONObject context=MemoryAnswerSupport.context(request),range=context.getJSONObject("查询日期范围");MemoryAnswerSupport.check(range.getString("date").equals("2033-03-29")&&range.getString("zone").equals("America/Los_Angeles"),"Specific date lookup kept its original zone/calendar");JSONArray rows=context.getJSONArray("候选个人记忆"),evidence=new JSONArray();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i),time=row.optJSONObject("time");if(time!=null&&time.optString("state").equals("resolved"))MemoryAnswerSupport.check(time.optLong("start")<range.getLong("end")&&time.optLong("end")>range.getLong("start"),"No outside-date resolved material sent");else MemoryAnswerSupport.check(row.optString("queryTimeBasis").equals("undated_background")||row.optString("queryTimeBasis").equals("date_known"),"Unknown material calendar was marked");if(row.optString("title").equals("咖啡资料：日期范围内fixture"))evidence.put(row.getString("id"));}MemoryAnswerSupport.check(evidence.length()==1,"Exact dated material reached actual model request");evidence.put("not-a-candidate-fixture");return new JSONObject().put("intent","query").put("reply","2033年3月29日的咖啡资料，我找到这一份日期范围内的记录。").put("evidenceIds",evidence).put("memories",new JSONArray()).put("updates",new JSONArray()).toString();};
      ScheduleApiSmoke.run(getTargetContext(),server.url());server.healthy();report.put("checks",new JSONArray().put(new JSONObject().put("scenario","existing-local-and-specific-date-regressions").put("modelRequests",server.count()).put("queryOnly",true)));
    }
    else throw new IllegalArgumentException("Unknown memory answer test mode");
    report.put("status","passed");MemoryAnswerSupport.proof(this,mode,report);result.putString("status","passed");result.putString("verified",mode);result.putString("measurements",report.toString());finish(Activity.RESULT_OK,result);
  }catch(Throwable failure){try{if(report!=null){report.put("status","failed").put("lastScenario",MemoryAnswerSupport.lastScenario).put("error",failure.toString()).put("lastObservation",MemoryAnswerSupport.lastObservation);MemoryAnswerSupport.proof(this,mode,report);}}catch(Exception ignored){}result.putString("status","failed");result.putString("lastScenario",MemoryAnswerSupport.lastScenario);result.putString("error",failure.toString());if(MemoryAnswerSupport.lastObservation!=null)result.putString("lastObservation",MemoryAnswerSupport.lastObservation.toString());finish(Activity.RESULT_CANCELED,result);}}
}
