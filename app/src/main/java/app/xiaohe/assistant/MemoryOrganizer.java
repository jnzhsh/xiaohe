package app.xiaohe.assistant;

import android.content.Context;
import org.json.*;
import java.util.regex.Pattern;

/** Reorganize existing records using their original dates, with an explicit report. */
public final class MemoryOrganizer {
  private static final Pattern RELATIVE=Pattern.compile("大后天|后天|前天|明天|今天|昨天|今晚|(?:上|下|本|这)周|上次|上一次|之前|那天|改天|找时间|下次");
  private MemoryOrganizer(){}
  public static JSONObject run(Store store,Context context)throws Exception {
    JSONArray records=store.all("memories"),remaining=new JSONArray();int changed=0,left=0;
    for(int i=0;i<records.length();i++){
      JSONObject record=records.getJSONObject(i);String original=record.optString("title")+" "+record.optString("content");
      if(!RELATIVE.matcher(original).find())continue;
      String before=record.toString(),sourceId=record.optString("dateNormalizationSourceId",record.optString("sourceId"));
      JSONObject source=sourceId.isEmpty()?null:store.one("messages",sourceId);
      if(source!=null&&TimeAnchor.anchor(source)>0){
        AbsoluteDates.capture(source);store.put("messages",source);AbsoluteDates.annotate(record,source);
        if(!before.equals(record.toString())){record.put("changeReason","本机整理：依据原始输入时间固定日期");store.put("memories",record);changed++;}
      }
      String shown=record.optString("dateNormalizedTitle",record.optString("title"))+" "+record.optString("dateNormalizedContent",record.optString("content"));
      if(RELATIVE.matcher(shown).find()){
        left++;if(remaining.length()<10)remaining.put(new JSONObject().put("id",record.getString("id")).put("title",record.optString("dateNormalizedTitle",record.optString("title","一条时间线索"))));
      }
    }
    int archived=store.archive(true);
    String report="整理完成，固定了 "+changed+" 条记录的日期，归档了 "+archived+" 条已完成的临时记录。";
    if(left>0)report+="还有 "+left+" 条引用或未定时间保留原话，可以看看来由。";
    else if(changed==0&&archived==0)report="已经检查过了，目前没有需要改写的相对日期，也没有完成满 7 天、可以归档的临时记录。";
    context.getSharedPreferences("maintenance",0).edit().putString("report",report).apply();
    return new JSONObject().put("archived",archived).put("datesNormalized",changed).put("datesRemaining",left).put("remainingDates",remaining).put("report",report);
  }
}
