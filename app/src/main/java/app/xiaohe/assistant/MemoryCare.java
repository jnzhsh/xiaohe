package app.xiaohe.assistant;

import org.json.*;

/** A record may be uncertain without asking the user to do anything. */
public final class MemoryCare {
  private MemoryCare(){}
  public static boolean requestsReminder(String text){String s=text==null?"":text;return (s.contains("提醒")||s.matches("(?is).*(remind me|set (?:a )?reminder).*"))&&!s.matches("(?is).*((不用|不要|不需要|别|无需|取消|停止|不必)(?!忘).{0,5}提醒|(?:don't|do not|no need to).{0,8}remind).*");}
  private static boolean requested(JSONObject r){return r.optBoolean("reminderRequested")||r.optBoolean("reminderConfirmed")||r.optLong("remindAt")>0||r.optJSONObject("reminderTime")!=null;}
  private static String sourceId(JSONObject r){return r.optString("careSourceId",r.optString("sourceId"));}
  private static boolean deferred(JSONObject r){return r.optBoolean("careDeferred")&&r.optString("careDeferredSourceId").equals(sourceId(r));}
  private static boolean unsafeMeaning(JSONObject r){String truth=r.optString("truthState");return truth.equals("disputed")||truth.equals("retracted")||truth.equals("historical")||r.optString("referenceState").equals("unresolved")||r.optBoolean("structuredReview");}
  public static boolean canAutoRemind(JSONObject r){if(r==null||!r.optString("status","active").equals("active")||r.optString("processing").equals("pending")||r.optBoolean("reminderFired")||deferred(r)||r.optLong("remindAt")<=0||unsafeMeaning(r))return false;if(r.optBoolean("reminderConfirmed"))return true;JSONObject t=r.optJSONObject("reminderTime");return TimeAnchor.resolved(t)&&t.optString("precision").equals("minute")&&t.optString("source").equals("user")&&t.optLong("start")==r.optLong("remindAt");}
  public static boolean actionable(JSONObject r){if(r==null||!r.optString("status","active").equals("active")||r.optString("processing").equals("pending")||r.optBoolean("reminderFired")||deferred(r)||!requested(r)||canAutoRemind(r))return false;String truth=r.optString("truthState");return !truth.equals("retracted")&&!truth.equals("historical");}
  public static boolean canArchive(JSONObject r){return r!=null&&!r.optString("processing").equals("pending")&&!actionable(r);}
  public static JSONObject inspect(JSONObject r)throws JSONException {
    JSONObject t=r.optJSONObject("time"),rt=r.optJSONObject("reminderTime");boolean flexible=(r.optString("kind").equals("intent")||r.optString("kind").equals("event")&&r.optString("eventState").equals("planned"))&&(t==null||!TimeAnchor.resolved(t))&&(t==null||t.optString("raw").isEmpty()||t.optString("state").equals("unscheduled"));boolean uncertain=r.optString("truthState").equals("disputed")||r.optString("referenceState").equals("unresolved")||r.optBoolean("structuredReview")||(t!=null&&!t.optString("raw").isEmpty()&&!TimeAnchor.resolved(t)&&!flexible);boolean action=actionable(r);String kind="",question="";
    if(action){if(r.optString("referenceState").equals("unresolved")){kind="reminder_subject";String label=Store.shorten(r.optString("subjectLabel","这个对象"),24);question="这次提醒说的是哪位"+label+"？";}else if(r.optString("truthState").equals("disputed")){kind="reminder_conflict";question="这次提醒要按哪种说法？";}else if(r.optBoolean("structuredReview")){kind="reminder_content";question="这次提醒要按现在的文字安排吗？";}else{kind="reminder_time";JSONObject clock=rt==null?t:rt;if(clock!=null&&clock.optBoolean("needsMeridiem"))question="是早上还是晚上"+clock.optInt("candidateHour")+"点提醒你？";else if(TimeAnchor.resolved(clock)&&clock.optString("precision").equals("day")){String raw=Store.shorten(clock.optString("raw"),24);question=(raw.isEmpty()?"那天":raw)+"几点提醒你？";}else question="想在哪天、几点提醒你？";}}
    return new JSONObject().put("level",action?"actionable":uncertain?"optional":"quiet").put("kind",kind).put("question",question).put("deferred",deferred(r)).put("sourceId",sourceId(r)).put("semanticUncertain",uncertain).put("canAutoRemind",canAutoRemind(r)).put("canArchive",canArchive(r)).put("timeFlexible",flexible);
  }
}
