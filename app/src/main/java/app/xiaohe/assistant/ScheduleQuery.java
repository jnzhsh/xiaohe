package app.xiaohe.assistant;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/** Calendar questions use the question's calendar, independently of retrieval keywords. */
public final class ScheduleQuery {
  private static final int DISPLAY_LIMIT=40,BATCH_SIZE=16;
  private static final Pattern DATE=Pattern.compile("(?:\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|(?:\\d{4}年)?\\d{1,2}月\\d{1,2}(?:日|号)?|大后天|后天|明天|今天|今晚|昨天|前天|(?:(?:上|下|本|这)?(?:周|星期|礼拜)[一二三四五六日天])|上周|下周|本周|这周)");
  private static final Pattern WRITES=Pattern.compile("记一下|记下|记住|帮我记|记录|保存|收下|提醒我|提醒一下|改期|改到|改成|修改|取消|延期|推迟|完成|做完|归档|恢复|预约一下|帮我安排|给我安排|制定|怎么安排|如何安排|建议|推荐");
  private static final Pattern OVERVIEW_WORDS=Pattern.compile("麻烦|请|帮我|帮忙|替我|让我|告诉我|我的|我|自己的|自己|看看|看一下|看下|查看|查询|查一下|查查|列出|列一下|待办事项|日程表|安排|日程|待办|任务|计划|预约|事情|事项|活动|列表|清单|有什么|有哪些|有啥|有何|有没有|有无|还有|没有|是不是|是否|什么|哪些|要做|要干|要忙|需要做|需要|全部|所有|其他|别的|另外|已经|现在|目前|接下来|上午|下午|晚上|中午|凌晨|早上|早晨|有|无|是|吗|么|呢|吧|的|要|做|干|忙|没|还|事");
  private static final DateTimeFormatter DAY=DateTimeFormatter.ofPattern("yyyy年M月d日",Locale.SIMPLIFIED_CHINESE),CLOCK=DateTimeFormatter.ofPattern("HH:mm",Locale.ROOT);
  private static final Comparator<JSONObject> ORDER=Comparator.comparingLong((JSONObject r)->displayTime(r).optLong("start")).thenComparing(r->r.optString("id"));
  private ScheduleQuery(){}

  public static boolean isScheduleQuestion(JSONObject source){
    if(source==null||!source.optString("role","user").equals("user"))return false;JSONArray files=source.optJSONArray("files");if(files!=null&&files.length()>0)return false;
    String text=source.optString("content").trim();if(text.isEmpty()||text.length()>220||text.contains("http://")||text.contains("https://")||!source.optString("linkText").isEmpty()||WRITES.matcher(text).find()||!DATE.matcher(text).find()||text.matches("(?s).*(好吗|好不好|合适吗|怎么样|行不行|能不能|可以吗).*"))return false;
    // A concrete-time or history question needs more interpretation than a calendar overview.
    if(text.matches("(?s).*(几点|什么时候|做了|干了|发生过|[0-9]{1,2}[:：][0-9]{1,2}|[零一二三四五六七八九十两0-9]+点).*"))return false;
    // Unknown words may name a person, course or topic. Leave those filters to the AI path.
    String overview=DATE.matcher(text).replaceAll("");overview=OVERVIEW_WORDS.matcher(overview).replaceAll("").replaceAll("[\\s，。？?！!：:]","");if(!overview.isEmpty())return false;
    boolean calendar=text.matches("(?s).*(安排|日程|待办|任务|计划|预约).*"),question=text.matches("(?s).*(什么|哪些|有啥|有何|有没有|有无|吗|么|呢|？|\\?|看看|看一下|查看|查询|查一下|查查|列出|列一下|告诉我).*"),things=text.matches("(?s).*(有什么事|有哪些事|有啥事|要做什么|要干什么|要忙什么|需要做什么).*" );
    if(calendar&&question||things)return true;
    String bare=DATE.matcher(text).replaceAll("").replaceAll("[\\s，。？?！!：:]","");return bare.matches("(?:我|我的)?(?:的)?(?:安排|日程|待办|任务|计划)(?:列表|清单)?");
  }

  /** Also available to the AI path when a question is too specific for the local overview. */
  public static JSONObject range(JSONObject source)throws JSONException {
    String text=source.optString("content"),zone=TimeAnchor.zone(source);ZoneId tz=ZoneId.of(zone);Matcher dates=DATE.matcher(text);JSONObject period=null;String token="";int matched=0;
    while(dates.find()){String raw=dates.group();JSONObject t=TimeAnchor.resolve(raw,source,false,null,"future");if(!TimeAnchor.resolved(t))return new JSONObject().put("state",t.optString("state")).put("raw",raw).put("zone",zone).put("sourceCalendarDate",Instant.ofEpochMilli(TimeAnchor.anchor(source)).atZone(tz).toLocalDate().toString());if(period!=null&&(period.optLong("start")!=t.optLong("start")||period.optLong("end")!=t.optLong("end")))return null;period=t;token=raw;matched++;}
    if(matched==0)return null;long start=period.getLong("start"),end=period.getLong("end");String precision=period.optString("precision");LocalDate date=Instant.ofEpochMilli(start).atZone(tz).toLocalDate();String part="";
    if(!precision.equals("week")){if(text.contains("上午"))part="上午";else if(text.contains("下午"))part="下午";else if(text.contains("晚上")||token.equals("今晚"))part="晚上";else if(text.contains("中午"))part="中午";else if(text.contains("凌晨"))part="凌晨";else if(text.contains("早上")||text.contains("早晨"))part="早上";if(!part.isEmpty()){int a=part.equals("下午")?12:part.equals("晚上")?18:part.equals("中午")?11:part.equals("早上")?6:0,b=part.equals("上午")||part.equals("早上")?12:part.equals("下午")?18:part.equals("中午")?14:part.equals("凌晨")?6:24;start=date.atTime(a,0).atZone(tz).toInstant().toEpochMilli();end=b==24?date.plusDays(1).atStartOfDay(tz).toInstant().toEpochMilli():date.atTime(b,0).atZone(tz).toInstant().toEpochMilli();precision="part_of_day";}}
    LocalDate first=Instant.ofEpochMilli(start).atZone(tz).toLocalDate(),last=Instant.ofEpochMilli(end-1).atZone(tz).toLocalDate();String label=DAY.format(first)+(first.equals(last)?part:"至"+DAY.format(last));return new JSONObject().put("state","resolved").put("date",first.toString()).put("startDate",first.toString()).put("endDate",last.toString()).put("start",start).put("end",end).put("zone",zone).put("label",label).put("precision",precision).put("raw",token).put("assumed",period.optBoolean("assumed")).put("sourceCalendarDate",Instant.ofEpochMilli(TimeAnchor.anchor(source)).atZone(tz).toLocalDate().toString()).put("capturedAt",TimeAnchor.anchor(source));
  }

  public static JSONObject answer(Store store,JSONObject source)throws Exception {synchronized(store){return answer(store.getReadableDatabase(),source);}}
  /** The overload allows tests to use an isolated database without opening personal settings. */
  public static JSONObject answer(SQLiteDatabase db,JSONObject source)throws Exception {
    if(!isScheduleQuestion(source))return null;JSONObject query=range(source);if(query==null)return null;
    if(!query.optString("state").equals("resolved"))return result("你说的“"+query.optString("raw")+"”没有解析成有效日期，我先不替你猜。可以换一个明确日期再查。",new JSONArray(),new JSONArray(),query.put("count",0).put("truncated",false));
    long start=query.getLong("start"),end=query.getLong("end");List<JSONObject> selected=new ArrayList<>();int[] total={0};List<String> batch=new ArrayList<>();
    // event_end is JSON, so earlier active intervals must also be checked for overlap.
    // Only small indexed columns are scanned; JSON is read in bounded batches, never a bootstrap window.
    String sql="SELECT id,event_start FROM memories INDEXED BY memory_truth_time WHERE truth_state='current' AND status='active' AND event_start>0 AND event_start<? AND (kind='intent' OR (kind='event' AND event_state IN ('planned','')) OR (kind='legacy' AND type='task')) ORDER BY event_start ASC,created ASC,id ASC";
    try(Cursor c=db.rawQuery(sql,new String[]{Long.toString(end)})){while(c.moveToNext()){batch.add(c.getString(0));if(batch.size()>=BATCH_SIZE){collect(db,batch,start,end,query,selected,total);batch.clear();}}}if(!batch.isEmpty())collect(db,batch,start,end,query,selected,total);
    batch.clear();String partial="SELECT id FROM memories INDEXED BY memory_truth_time WHERE truth_state='current' AND event_start=0 AND status='active' AND (kind='intent' OR (kind='event' AND event_state IN ('planned',''))) AND data LIKE '%\"dateStart\":%'";try(Cursor c=db.rawQuery(partial,null)){while(c.moveToNext()){batch.add(c.getString(0));if(batch.size()>=BATCH_SIZE){collect(db,batch,start,end,query,selected,total);batch.clear();}}}if(!batch.isEmpty())collect(db,batch,start,end,query,selected,total);
    selected.sort(ORDER);JSONArray rows=new JSONArray(),evidence=new JSONArray();for(JSONObject r:selected){rows.put(r);evidence.put(r.getString("id"));}
    query.put("count",total[0]).put("truncated",total[0]>selected.size());String label=query.getString("label"),reply;
    if(rows.length()==0)reply="我目前没有找到记在 "+label+" 的有效安排。时间还没定的事项，没有算进这一天。";
    else{StringBuilder text=new StringBuilder(label).append("在本机找到 ").append(total[0]).append(" 项已记下的安排：");ZoneId zone=ZoneId.of(query.getString("zone"));for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i),t=displayTime(r);ZonedDateTime at=Instant.ofEpochMilli(t.getLong("start")).atZone(zone);boolean week=t.optString("precision").equals("week");String when=week?"周内，具体日期和时刻未定":t.optString("precision").equals("minute")?CLOCK.format(at):"时刻未定";if(!week&&(!query.getString("startDate").equals(query.getString("endDate"))||t.optLong("start")<start))when=DAY.format(at.toLocalDate())+" "+when;if(!week&&t.optLong("start")<start)when+="（延续到查询时段）";String description=Store.shorten(r.optString("title",r.optString("content")).replaceAll("[\\r\\n]+"," ").trim(),130);if(description.isEmpty())description=Store.shorten(r.optString("content"),130);if(description.matches("(?s).*(明天|今天|昨天|后天|前天|这周|下周|上周).*"))description="原记录“"+description+"”";text.append("\n").append(i+1).append(". ").append(when).append("，").append(description);if(r.optString("referenceState").equals("unresolved"))text.append("（对象按原话保留）");}if(total[0]>rows.length())text.append("\n先列出前 ").append(rows.length()).append(" 项；还有 ").append(total[0]-rows.length()).append(" 项，可以在待办中继续查看。");reply=text.toString();}
    return result(reply,evidence,rows,query);
  }

  private static void collect(SQLiteDatabase db,List<String> ids,long start,long end,JSONObject query,List<JSONObject> selected,int[] total)throws Exception {
    StringJoiner placeholders=new StringJoiner(",");for(String id:ids)placeholders.add("?");JSONArray rows=Store.jsonRows(db,"SELECT data FROM memories WHERE id IN ("+placeholders+")",ids.toArray(new String[0]));List<JSONObject> ordered=new ArrayList<>();for(int i=0;i<rows.length();i++)ordered.add(rows.getJSONObject(i));ordered.sort(Comparator.comparingLong((JSONObject r)->r.optJSONObject("time")==null?0:r.optJSONObject("time").optLong("start")).thenComparing(r->r.optString("id")));
    for(JSONObject r:ordered){if(!effective(r))continue;JSONObject t=timeForQuery(r,query);if(t==null||t.optLong("start")<=0)continue;long a=t.optLong("start"),b=t.optLong("end",a+1);if(b<=a)b=a+1;if(a>=end||b<=start)continue;if(t.optString("precision").equals("week")&&!query.optString("precision").equals("week"))continue;if(!TimeAnchor.resolved(r.optJSONObject("time")))r.put("scheduleTime",t);total[0]++;if(selected.size()<DISPLAY_LIMIT)selected.add(r);else{JSONObject worst=Collections.max(selected,ORDER);if(ORDER.compare(r,worst)<0){selected.remove(worst);selected.add(r);}}}
  }
  private static JSONObject displayTime(JSONObject r){return r.optJSONObject("scheduleTime")==null?r.optJSONObject("time"):r.optJSONObject("scheduleTime");}
  /** A known event date is usable for overview even when its AM/PM remains unknown. */
  public static JSONObject timeForQuery(JSONObject r,JSONObject query)throws JSONException {JSONObject t=r.optJSONObject("time");if(TimeAnchor.resolved(t))return t;if(t==null||!t.optString("state").equals("ambiguous_clock")||t.optLong("dateStart")<=0)return null;JSONObject anchor=t.optJSONObject("anchor");String zone=anchor==null?query.optString("zone"):anchor.optString("zone",query.optString("zone"));try{ZoneId tz=ZoneId.of(zone);long start=t.getLong("dateStart");LocalDate date=Instant.ofEpochMilli(start).atZone(tz).toLocalDate();return new JSONObject().put("state","date_known").put("precision","day").put("start",start).put("end",date.plusDays(1).atStartOfDay(tz).toInstant().toEpochMilli()).put("raw",t.optString("raw")).put("clockUncertain",true);}catch(DateTimeException e){return null;}}
  private static boolean effective(JSONObject r){String status=r.optString("status","active"),truth=r.optString("truthState","current"),event=r.optString("eventState");return status.equals("active")&&truth.equals("current")&&!r.optBoolean("structuredReview")&&!r.optString("processing").equals("pending")&&!r.optBoolean("cancelled")&&!r.optBoolean("canceled")&&r.optLong("cancelledAt")==0&&r.optLong("canceledAt")==0&&!Arrays.asList("cancelled","canceled","completed","occurred","superseded").contains(event)&&r.optString("supersededBy").isEmpty();}
  private static JSONObject result(String reply,JSONArray evidence,JSONArray rows,JSONObject query)throws JSONException {return new JSONObject().put("intent","query").put("reply",reply).put("evidenceIds",evidence).put("memories",new JSONArray()).put("updates",new JSONArray()).put("local",true).put("queryTime",query).put("candidates",rows);}
}
