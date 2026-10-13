package app.xiaohe.tests;

import android.app.Instrumentation;
import app.xiaohe.assistant.*;
import org.json.*;

/** Read-only production-code investigation. Reproduces existing behavior; it is not a fix. */
public final class FeedbackInvestigationSmoke {
  public static JSONObject run(Instrumentation test)throws Exception{
    try(FeedbackSupport.Fixture fixture=new FeedbackSupport.Fixture(test)){
      Store db=fixture.scope.db;JSONObject source=FeedbackSupport.message(db,"user","测试条目：请查一下蓝色纸船",null);db.pendingRecord(source,"synthetic earlier processing failure");JSONArray memories=db.all("memories");FeedbackSupport.check(memories.length()==1,"Synthetic earlier failure creates one pending record");JSONObject pending=memories.getJSONObject(0);String pendingId=pending.getString("id");
      FeedbackSupport.check(db.one("memories",pendingId)!=null&&pending.optString("sourceId").equals(source.getString("id")),"Candidate evidence exists and belongs to this source before apply");JSONArray candidates=new JSONArray().put(new JSONObject(pending.toString()));JSONArray evidence=new JSONArray().put(pendingId);source.put("recalledIds",evidence);
      JSONObject answer=new JSONObject().put("intent","query").put("reply","合成查询结果").put("evidenceIds",evidence).put("memories",new JSONArray()).put("updates",new JSONArray());new MemoryLedger(db).apply(source,answer,candidates);
      boolean missing=db.one("memories",pendingId)==null;boolean retainedReference=db.one("messages",source.getString("id")).getJSONArray("recalledIds").optString(0).equals(pendingId);
      FeedbackSupport.check(missing&&retainedReference,"Existing apply deletes same-source pending evidence while retaining its verified reference");
      return new JSONObject().put("investigationOnly",true).put("productionCodeChanged",false).put("observedExistingBehavior","same-source pending candidate removed by successful query apply while evidence reference remains").put("candidateExistedBefore",true).put("candidateMissingAfter",missing).put("referenceStillSaved",retainedReference).put("userScreenshotRootCauseConfirmed",false).put("limitation","Synthetic direct ledger reproduction; the private screenshot postdates the supplied backup, so actual retrieval and model output cannot be reconstructed from it.");
    }
  }
}
