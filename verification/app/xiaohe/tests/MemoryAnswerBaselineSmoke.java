package app.xiaohe.tests;

import android.app.Instrumentation;
import app.xiaohe.assistant.*;
import org.json.*;

/** Passing this mode means the old defects were observed, not that the old behavior is correct. */
final class MemoryAnswerBaselineSmoke {
  static JSONArray run(Instrumentation test)throws Exception{JSONArray evidence=new JSONArray();try(MemoryAnswerSupport.Session s=new MemoryAnswerSupport.Session(test)){
    MemoryAnswerSupport.Gifts gifts=new MemoryAnswerSupport.Gifts(s.db(),true);
    MemoryAnswerSupport.lastScenario="baseline-first-person";
    s.server.responder=request->MemoryAnswerSupport.query("合成甲那笔是1200元。",gifts.a);
    JSONObject first=s.stage("合成甲婚礼那次，我给了多少份子钱？");s.process(first);
    MemoryAnswerSupport.check(MemoryAnswerSupport.has(MemoryAnswerSupport.candidates(s.server.last()),gifts.a.getString("id")),"Exact first-person evidence was retrieved");
    MemoryAnswerSupport.lastScenario="baseline-plural-recall";
    s.server.responder=request->MemoryAnswerSupport.query("我需要再问问他们的金额。");
    JSONObject plural=s.stage("现在轮到我，他们该给我回多少");JSONObject before=MemoryAnswerSupport.digest(s.db());JSONObject answer=s.process(plural);
    JSONArray candidates=MemoryAnswerSupport.candidates(s.server.last());MemoryAnswerSupport.check(!MemoryAnswerSupport.has(candidates,gifts.b.getString("id")),"Old scalar-focus retrieval omitted the other known person's archived gift");MemoryAnswerSupport.readonly(before,s.db());
    evidence.put(new JSONObject().put("scenario","old-plural-recall").put("defectObserved",true).put("sentIds",MemoryAnswerSupport.ids(candidates)).put("missingKnownGiftId",gifts.b.getString("id")).put("reply",answer.getString("content")));
    MemoryAnswerSupport.lastScenario="baseline-model-record-on-question";
    s.server.responder=request->new JSONObject().put("intent","record").put("reply","已经记下将收到9999元。").put("evidenceIds",new JSONArray()).put("memories",new JSONArray().put(new JSONObject().put("title","凭空出现的未来实收").put("content","我将收到9999元回礼。").put("type","task").put("kind","intent").put("tags",new JSONArray().put("合成反馈")))).put("updates",new JSONArray()).toString();
    JSONObject recordQuestion=s.stage("现在轮到我，他们该给我回多少");int prior=s.db().all("memories").length();s.process(recordQuestion);int added=s.db().all("memories").length()-prior;MemoryAnswerSupport.check(added>0,"Old query trusted record intent and wrote a made-up future receipt");evidence.put(new JSONObject().put("scenario","old-query-written-as-record").put("defectObserved",true).put("addedMemories",added));
    MemoryAnswerSupport.lastScenario="baseline-503-pending-query";
    s.server.status=503;s.server.responder=request->MemoryAnswerSupport.query("合成接口不可用。");JSONObject failed=s.stage("现在轮到我，他们该给我回多少");s.process(failed);JSONArray rows=s.db().all("memories");int pending=0;for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if(r.optString("sourceId").equals(failed.getString("id"))&&r.optString("processing").equals("pending"))pending++;}MemoryAnswerSupport.check(pending>0,"Old failure fallback saved a no-question-mark query as pending memory");evidence.put(new JSONObject().put("scenario","old-503-query-pending").put("defectObserved",true).put("pendingForQuery",pending));
  }return evidence;}
}
