package app.xiaohe.assistant;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.*;

/** An attached memory is a versioned mutation target, never a search hint or new record. */
public final class ChatMemoryEdit {
  private final Context context;private final Store store;
  private static final Set<String> TOP=new HashSet<>(Arrays.asList("intent","targetId","reply","quote","patch"));
  private static final Set<String> PATCH=new HashSet<>(Arrays.asList("title","contentEdits","status","temporal","reminder","fact","references","tags","longTerm"));
  private static final Pattern DATE=Pattern.compile("(?:\\d{4}年)?\\d{1,2}月\\d{1,2}(?:日|号)?|(?:19|20)\\d{2}[-/]\\d{1,2}[-/]\\d{1,2}|大后天|后天|前天|明天|今天|昨天|今晚|(?:上|下|本|这)?(?:周|星期|礼拜)[一二三四五六日天]|(?:上|下|本|这)周");
  private static final Pattern CLOCK=Pattern.compile("(?:下午|晚上|今晚|傍晚|上午|凌晨|早上|早晨|中午)?\\s*(?:[0-9]{1,2}[:：][0-9]{1,2}|[零一二三四五六七八九十两0-9]+(?:点|时)(?:半|[零一二三四五六七八九十两0-9]+分?)?)");
  private static final Pattern HIDDEN=Pattern.compile("https?://[^\\s<>\"，。；]+|```[\\s\\S]*?```|`[^`]*`");
  public ChatMemoryEdit(Context context){this.context=context.getApplicationContext();store=Store.get(this.context);}
  public JSONObject context(String id)throws Exception {synchronized(store){JSONObject row=record(id);return new JSONObject().put("id",id).put("revision",revision(row)).put("title",row.optString("dateNormalizedTitle",row.optString("title"))).put("summary",cut(row.optString("dateNormalizedContent",row.optString("content")),240));}}
  public JSONObject capture(JSONObject requested)throws Exception {
    synchronized(store){
    if(requested==null)throw new Exception("这次修改的目标没有附上，可以重新选一下。");String id=text(requested,"id");Object raw=requested.opt("revision");
    if(!(raw instanceof Number)||((Number)raw).doubleValue()!=((Number)raw).intValue()||((Number)raw).intValue()<1)throw new Exception("这条记忆的版本没读完整，请重新选一下。");
    JSONObject row=record(id);if(revision(row)!=((Number)raw).intValue())throw stale();
    return new JSONObject().put("id",id).put("revision",revision(row)).put("title",row.optString("dateNormalizedTitle",row.optString("title"))).put("summary",cut(row.optString("dateNormalizedContent",row.optString("content")),240)).put("fingerprint",fingerprint(row));}
  }
  private JSONObject record(String id)throws Exception {if(!id.matches("[a-zA-Z0-9_-]{8,80}"))throw new Exception("这条记忆暂时没找到，请重新选一下。");JSONObject row=store.one("memories",id);if(row==null)throw new Exception("这条记忆已经不在了，原话留着，重新选好目标再说就可以。");return row;}
  private JSONObject current(JSONObject source)throws Exception {JSONObject attached=source.optJSONObject("editContext");if(attached==null)throw new Exception("这次修改还没有指定目标。");JSONObject row=record(attached.getString("id"));if(revision(row)!=attached.optInt("revision")||!fingerprint(row).equals(attached.optString("fingerprint")))throw stale();return row;}
  private static Exception stale(){return new Exception("这条记忆后来有了变化，这次先没改。原话留着，重新选一下最新版本就好。");}
  private static int revision(JSONObject row){return Math.max(1,row.optInt("revision",1));}
  private static String fingerprint(JSONObject row)throws Exception {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(row.toString().getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
  private static String committedFingerprint(JSONObject row)throws Exception {JSONObject copy=new JSONObject(row.toString());copy.remove("revision");copy.remove("reminderSchedule");return fingerprint(copy);}
  private static String cut(String value,int count){return value.codePointCount(0,value.length())<=count?value:value.substring(0,value.offsetByCodePoints(0,count-1))+"…";}
  private static String text(JSONObject object,String key)throws Exception {Object value=object.opt(key);if(!(value instanceof String))throw new Exception("模型没有给出可核对的修改内容，原记录先保留。");return (String)value;}
  private static void keys(JSONObject object,Set<String> allowed)throws Exception {Iterator<String> all=object.keys();while(all.hasNext())if(!allowed.contains(all.next()))throw new Exception("模型还提出了这次目标以外的改动，原记录先保留。");}
  private static JSONArray array(JSONObject object,String key){JSONArray value=object.optJSONArray(key);return value==null?new JSONArray():value;}
  private static void add(JSONArray values,String id)throws JSONException {for(int i=0;i<values.length();i++)if(values.optString(i).equals(id))return;values.put(id);}

  public JSONObject modelTarget(JSONObject row)throws Exception {
    JSONObject value=new JSONObject();for(String key:new String[]{"id","revision","title","dateNormalizedTitle","type","kind","status","truthState","structuredReview","processingNote","referenceState","unresolvedRelated","subjectId","subjectLabel","predicate","value","scope","entityIds","tags","longTerm","time","reminderTime","remindAt","reminderRequested","reminderConfirmed","eventState"})if(row.has(key))value.put(key,row.get(key));
    value.put("content",cut(row.optString("content"),12000)).put("dateNormalizedContent",cut(row.optString("dateNormalizedContent"),12000)).put("contentTruncated",row.optString("content").codePointCount(0,row.optString("content").length())>12000);
    JSONArray entities=new JSONArray();LinkedHashSet<String> ids=new LinkedHashSet<>();for(int i=0;i<array(row,"entityIds").length();i++)ids.add(array(row,"entityIds").optString(i));if(!row.optString("subjectId").isEmpty())ids.add(row.getString("subjectId"));
    for(String id:ids){JSONObject entity=store.one("entities",id);if(entity==null)continue;JSONObject compact=new JSONObject();for(String key:new String[]{"id","name","kind","qualifier"})if(entity.has(key))compact.put(key,entity.get(key));entities.put(compact);}value.put("entities",entities);return value;
  }
  public JSONObject process(JSONObject input,AiEngine engine)throws Exception {
    return process(input,engine,new JSONArray());
  }
  public JSONObject process(JSONObject input,AiEngine engine,JSONArray frames)throws Exception {
    JSONObject source=store.one("messages",input.getString("id"));if(source==null)throw new Exception("这轮原话暂时没有找到。");
    if(source.optJSONObject("editResult")==null){JSONObject target=current(source);JSONObject proposed=engine.proposeMemoryEdit(source,modelTarget(target),frames);apply(source,proposed,frames);}
    return finish(source.getString("id"));
  }
  public JSONObject apply(JSONObject input,JSONObject proposed)throws Exception {return apply(input,proposed,new JSONArray());}
  public JSONObject apply(JSONObject input,JSONObject proposed,JSONArray frames)throws Exception {synchronized(store){
    JSONObject source=store.one("messages",input.getString("id"));if(source==null)throw new Exception("这轮原话暂时没有找到。");if(source.optJSONObject("editResult")!=null)return source.getJSONObject("editResult");
    JSONObject target=current(source);keys(proposed,TOP);String intent=text(proposed,"intent"),id=text(proposed,"targetId");
    if(!id.equals(target.getString("id"))||!Arrays.asList("query","update").contains(intent))throw new Exception("模型没有只针对选中的这一条处理，原记录先保留。");
    JSONObject patch=proposed.optJSONObject("patch");if(patch==null)patch=new JSONObject();keys(patch,PATCH);
    if(intent.equals("query")&&patch.length()>0)throw new Exception("这次是询问，模型却提出了改动，原记录先保留。");
    if(intent.equals("update")){String quote=text(proposed,"quote");quoted(source,quote);if(readOnlyQuestion(source.optString("content")))throw new Exception("这次是在询问这条记忆，我先保留原来的内容。");if(patch.length()==0)throw new Exception("模型没有给出可核对的修改，原记录先保留。");if(!meridiemOnly(source.optString("content")).isEmpty()&&(patch.length()!=1||!patch.has("reminder")))throw new Exception("这句是在补充提醒的上午或晚上，其他内容先保留。");}
    SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{
      JSONObject result;JSONArray records=new JSONArray(),schedules=new JSONArray(),expected=new JSONArray();
      if(intent.equals("query")){String reply=proposed.optString("reply").trim();if(reply.isEmpty()||reply.matches("(?s).*(已经|已|帮你|替你).{0,8}(改好|改成|修改|完成|取消|归档|更新|安排|设置|记下).*"))reply="这条记忆目前是："+cut(target.optString("dateNormalizedContent",target.optString("content")),700);result=new JSONObject().put("intent",intent).put("changed",false).put("reply",reply);source.put("recalledIds",new JSONArray().put(id));}
      else {
        JSONObject edited=new JSONObject(target.toString());String operation="content";boolean scheduling=false;
        if(patch.has("fact")){
          if(!target.optString("kind").equals("fact")||patch.length()!=1)throw new Exception("事实变化只调整这条已确定事实的说法，其他字段先保留。");
          JSONObject fact=patch.getJSONObject("fact");keys(fact,new HashSet<>(Arrays.asList("mode","value","quote","source")));quoted(source,text(fact,"quote"));String value=text(fact,"value");if(value.trim().isEmpty())throw new Exception("新的事实说法不能为空。");materialValue(source,value,fact.optString("source","user"),null);
          JSONObject replacement=new MemoryLedger(store).transitionAttachedFact(target,source,fact);if(!replacement.getString("id").equals(target.getString("id"))){records.put(target.getString("id")).put(replacement.getString("id"));schedules.put(target.getString("id")).put(replacement.getString("id"));}edited=replacement;operation="fact";scheduling=true;
        } else {
          if(target.optString("kind").equals("fact")&&(patch.has("contentEdits")||patch.has("title")||patch.has("references")||patch.has("temporal")))throw new Exception("事实的文字、人物、属性或范围不能直接覆盖。可以说明是以前记错了，还是现在发生了变化。");
          if(patch.has("contentEdits"))edited.put("content",contentEdits(target.optString("content"),patch.getJSONArray("contentEdits"),source,frames));
          if(patch.has("title")){String title=text(patch,"title");if(title.trim().isEmpty()||title.codePointCount(0,title.length())>180||!source.optString("content").contains(title))throw new Exception("新标题还没能在原话里核对到，原记录先保留。");edited.put("title",title);}
          if(patch.has("references")){new MemoryLedger(store).replaceAttachedReferences(edited,target,source,patch.getJSONArray("references"));operation="references";}
          referenceTextConsistent(target,edited);
          if(patch.has("tags"))tags(edited,source,patch.get("tags"));
          if(patch.has("longTerm")){Object value=patch.get("longTerm");if(!(value instanceof Boolean))throw new Exception("长期保存的选择没读完整，原设置先保留。");String sourceText=source.optString("content");if((Boolean)value?!sourceText.matches("(?s).*(长期|一直保留|永久|一直留).*" ):!sourceText.matches("(?s).*(取消长期|不再长期|不用长期|临时|普通保存).*"))throw new Exception("这轮原话还没有明确改变长期保存，原设置先保留。");edited.put("longTerm",value);}
          if(patch.has("status")){status(edited,source,text(patch,"status"));operation="status";scheduling=true;}
          if(patch.has("temporal")){
            if(!Arrays.asList("intent","event").contains(target.optString("kind"))&&!Arrays.asList("task","event").contains(target.optString("type")))throw new Exception("这条资料还不是有日期的事项，这次先保留原内容。");
            JSONObject temporal=patch.getJSONObject("temporal");keys(temporal,new HashSet<>(Arrays.asList("raw","source")));String raw=text(temporal,"raw"),provenance=temporal.optString("source","user");materialValue(source,raw,provenance,null);
            boolean occurred=target.optString("kind").equals("event")&&target.optString("eventState").equals("occurred"),paused=target.optBoolean("structuredReview");
            JSONObject time=resolveTime(raw,source,paused?null:target.optJSONObject("time"),provenance,occurred?"past":"future");
            if(occurred&&day(time)>TimeAnchor.anchor(AbsoluteDates.frozen(source)))throw new Exception("这条记录是已经发生的事件，新的时间却在未来。这次先保留原记录，不会自动改成新计划。");
            edited.put("time",time).put("dateNormalizationSourceId",source.getString("id"));edited.remove("dateNormalizationSuppressed");if(TimeAnchor.resolved(time))edited.put("dueAt",time.optString("iso"));else edited.remove("dueAt");
            if(paused){
              boolean coherent=syncPausedTemporalText(edited,time),full=DATE.matcher(raw).find()&&CLOCK.matcher(raw).find()&&TimeAnchor.resolved(time)&&time.optString("precision").equals("minute");
              pauseReminder(edited);
              if(full&&coherent&&verifiableReferences(edited)){edited.remove("structuredReview");String note=edited.optString("processingNote");if(note.contains("原结构"))edited.remove("processingNote");}
              else edited.put("structuredReview",true).put("processingNote","这轮新日期已保存。原文字的时刻或对象尚未完整核对，安排仍未确定，旧提醒没有恢复。");
            }else {syncTemporalText(edited,target,time);moveReminder(edited,target,source,time);}
            operation="time";scheduling=true;
          }
          if(patch.has("reminder")){reminder(edited,source,patch.getJSONObject("reminder"));operation="reminder";scheduling=true;}
          if(!target.optString("kind").equals("fact"))for(String field:new String[]{"content","title","subjectLabel"})if(target.has("value")&&target.optString("value").equals(target.optString(field))&&!target.optString(field).equals(edited.optString(field)))edited.put("value",edited.optString(field));
          boolean changed=!edited.toString().equals(target.toString());
          if(changed){JSONArray sources=new JSONArray(array(target,"sourceIds").toString());add(sources,source.getString("id"));edited.put("sourceIds",sources).put("updated",System.currentTimeMillis()).put("lastChangeSourceId",source.getString("id")).put("changeReason","用户通过聊天修改选中的这一条："+source.optString("content")).put("userEdited",true);if(!edited.optString("content").equals(target.optString("content")))edited.put("dateNormalizationSourceId",source.getString("id")).remove("dateNormalizationSuppressed");store.put("memories",edited);records.put(edited.getString("id"));if(scheduling)schedules.put(edited.getString("id"));}
        }
        boolean changed=records.length()>0;result=new JSONObject().put("intent",intent).put("changed",changed).put("operation",operation).put("reply",changed?acknowledge(edited,operation):"这条已经是这个样子，不用重复修改。");source.put("memoryIds",new JSONArray().put(edited.getString("id"))).put("focusEntityIds",array(edited,"entityIds"));
      }
      for(int i=0;i<schedules.length();i++){JSONObject row=store.one("memories",schedules.getString(i));if(row!=null)expected.put(new JSONObject().put("id",row.getString("id")).put("fingerprint",committedFingerprint(row)));}
      result.put("targetId",id).put("recordIds",records).put("scheduleIds",schedules).put("scheduleSnapshots",expected).put("evidenceIds",new JSONArray().put(source.optJSONArray("memoryIds")!=null&&source.getJSONArray("memoryIds").length()>0?source.getJSONArray("memoryIds").getString(0):id));
      source.put("editResult",result);source.remove("error");store.put("messages",source);db.setTransactionSuccessful();return result;
    }finally{db.endTransaction();}
  }}
  private JSONObject finish(String id)throws Exception {
    JSONObject source=store.one("messages",id);if(source.optBoolean("processed"))return store.bootstrap();JSONObject result=source.getJSONObject("editResult");String reply=result.getString("reply");boolean notify=false,newer=false;
    JSONArray expected=array(result,"scheduleSnapshots");for(int i=0;i<expected.length();i++){synchronized(store){JSONObject snapshot=expected.getJSONObject(i),row=store.one("memories",snapshot.getString("id"));if(row==null||!committedFingerprint(row).equals(snapshot.optString("fingerprint"))){newer=true;continue;}
      try{JSONObject schedule=Scheduler.schedule(context,row);row.put("reminderSchedule",schedule);store.put("memories",row);if(schedule.optString("state").equals("scheduled")){notify|=!schedule.optBoolean("notificationsAllowed");if(!schedule.optBoolean("notificationsAllowed"))reply+="\n提醒时间也记好了，允许通知后才能提醒你。";else reply+="\n提醒也安排在 "+when(schedule.optLong("at"),row.optJSONObject("reminderTime"))+"。";}else if(row.optLong("remindAt")>0&&row.optString("status").equals("active"))reply+="\n内容已经保存，提醒这次还没安排成功。";}
      catch(Exception e){reply+="\n内容已经保存，提醒这次还没安排成功。";}
    }}
    if(newer)reply="这轮修改已经保存，这条记忆后来又有了变化。我保留了每次修改的版本。";
    synchronized(store){JSONObject latest=store.one("messages",id);if(!latest.optBoolean("processed")){SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{JSONObject response=store.reply(reply,id,null);response.put("evidenceIds",result.getJSONArray("evidenceIds"));store.put("messages",response);latest.put("processed",true).put("editReplyId",response.getString("id"));latest.remove("error");store.put("messages",latest);db.setTransactionSuccessful();}finally{db.endTransaction();}}}
    return store.bootstrap().put("editNeedsNotification",notify);
  }

  private static void quoted(JSONObject source,String value)throws Exception {if(value.trim().isEmpty()||!source.optString("content").contains(value))throw new Exception("修改依据还没能和这轮原话核对上，原记录先保留。");}
  /** A numeric fragment must be a whole value, not the middle of a larger amount. */
  static int wholeValueIndex(String content,String value,int from){if(value.isEmpty())return -1;boolean left=Character.isDigit(value.charAt(0)),right=Character.isDigit(value.charAt(value.length()-1));int at=Math.max(0,from);while((at=content.indexOf(value,at))>=0){int end=at+value.length();if((!left||at==0||!numberAdjacent(content.charAt(at-1)))&&(!right||end==content.length()||!numberAdjacent(content.charAt(end))))return at;at++;}return -1;}
  private static boolean numberAdjacent(char value){return Character.isDigit(value)||value=='.'||value=='．';}
  private static boolean readOnlyQuestion(String value){boolean request=value.matches("(?s).*(改|修改|调整|挪|推迟|延期|提前|换成|补充|添加|增加|归档|恢复|标记|标为|设置|设为|提醒我|取消这|不去了|不用做|重新安排).*"),suggestion=(value.contains("可以")||value.contains("好吗")||value.contains("行吗"))&&(DATE.matcher(value).find()||CLOCK.matcher(value).find());if(value.matches("(?s).*(别改|不要改|先别改|不要修改|先不要修改|不要更改|先别更改|先不改|只查询|只问问|完成了吗|做完了吗|取消了吗|归档了吗|恢复了吗|改过吗|调整过吗).*"))return true;if(request||suggestion)return false;return value.matches("(?s).*(什么时候|几点|哪天|是什么|有没有|是否|[？?]|吗[。！!]?\\s*$).*" );}
  private String materialText(JSONObject source,String provenance)throws Exception {
    if(provenance.equals("user"))return source.optString("content");if(provenance.equals("link")){if(!source.has("linkText"))throw new Exception("这轮链接还没读到可核对的文字，原记录先保留。");return source.optString("linkText");}
    boolean selected=false;for(int i=0;i<array(source,"files").length();i++)if(array(source,"files").optString(i).equals(provenance))selected=true;if(!selected)throw new Exception("模型引用了这轮没选中的材料，原记录先保留。");JSONObject file=store.one("files",provenance);if(file==null||file.optBoolean("localVoice"))throw new Exception("这份材料的文字暂时不能核对，语音以输入框里核对过的文字为准。");return file.optString("text")+"\n"+file.optString("ocrText");
  }
  private void materialValue(JSONObject source,String value,String provenance,JSONArray frames)throws Exception {
    String material=materialText(source,provenance);if(!value.trim().isEmpty()&&wholeValueIndex(material,value,0)>=0)return;
    if(!provenance.equals("user")&&!provenance.equals("link")&&frames!=null&&new Settings(context).flag("vision",false)){JSONObject file=store.one("files",provenance);if(file!=null&&(file.optString("mime").startsWith("image/")||file.optString("mime").startsWith("video/")))for(int i=0;i<frames.length();i++)if(provenance.equals(frames.getJSONObject(i).optString("sourceId")))return;}
    throw new Exception("修改文字还没能和本轮原话或选中资料核对上，原记录先保留。");
  }
  private String contentEdits(String original,JSONArray edits,JSONObject source,JSONArray frames)throws Exception {
    if(edits.length()==0||edits.length()>12)throw new Exception("这次文字改动还没整理清楚，原内容先保留。");String result=original;
    for(int i=0;i<edits.length();i++){JSONObject edit=edits.getJSONObject(i);keys(edit,new HashSet<>(Arrays.asList("op","old","text","source")));String op=text(edit,"op"),value=text(edit,"text");if(value.trim().isEmpty())throw new Exception("补充或替换的文字不能为空。");materialValue(source,value,edit.optString("source","user"),frames);if(op.equals("append")){result=result+(result.isEmpty()?"":"\n")+value;}else if(op.equals("replace")){String old=text(edit,"old");int at=wholeValueIndex(result,old,0);if(at<0||wholeValueIndex(result,old,at+old.length())>=0)throw new Exception("原内容里这段文字没能唯一找到，先没有替换，其他内容都还在。");result=result.substring(0,at)+value+result.substring(at+old.length());}else throw new Exception("模型提出了不支持的文字覆盖，完整原内容先保留。");}
    return result;
  }
  private void referenceTextConsistent(JSONObject before,JSONObject after)throws Exception {
    LinkedHashSet<String> ids=new LinkedHashSet<>();for(int i=0;i<array(before,"entityIds").length();i++)ids.add(array(before,"entityIds").getString(i));if(!before.optString("subjectId").isEmpty())ids.add(before.getString("subjectId"));
    Set<String> afterIds=new HashSet<>();for(int i=0;i<array(after,"entityIds").length();i++)afterIds.add(array(after,"entityIds").getString(i));if(!after.optString("subjectId").isEmpty())afterIds.add(after.getString("subjectId"));
    for(String id:ids){JSONObject entity=store.one("entities",id);if(entity==null||!afterIds.contains(id))continue;String name=entity.optString("name");if(name.length()<2)continue;for(String field:new String[]{"content","title"})if(before.optString(field).contains(name)&&!after.optString(field).contains(name))throw new Exception("文字里的对象变了，但对应人物或地点还没核对清楚，原记录先保留。");}
  }
  private static void status(JSONObject row,JSONObject source,String status)throws Exception {
    String input=source.optString("content");boolean allowed=status.equals("completed")?input.matches("(?s).*(完成|做完|搞定|办完|结束|学完|读完|看完|done|finished).*" ):status.equals("archived")?input.contains("归档"):status.equals("active")?input.matches("(?s).*(恢复|放回|重新|未完成|待办|取消完成).*" ):status.equals("cancelled")?input.matches("(?s).*(取消|不去了|不用做|不做了|不安排).*" ):false;if(!allowed)throw new Exception("这轮原话没有明确要求这个状态变化，原记录先保留。");
    if(status.equals("cancelled")){row.put("status","completed").put("completedAt",System.currentTimeMillis()).put("cancelledAt",System.currentTimeMillis()).put("eventState","cancelled");}
    else if(!row.optString("status").equals(status)){row.put("status",status);if(status.equals("completed"))row.put("completedAt",System.currentTimeMillis());if(status.equals("archived"))row.put("archivedAt",System.currentTimeMillis());if(status.equals("active")){row.remove("completedAt");row.remove("archivedAt");row.remove("cancelledAt");row.remove("cancelled");row.remove("canceledAt");row.remove("canceled");if(Arrays.asList("cancelled","canceled","completed").contains(row.optString("eventState")))row.put("eventState","planned");}}
  }
  private static void tags(JSONObject row,JSONObject source,Object requested)throws Exception {
    String input=source.optString("content");if(!input.contains("标签")&&!input.contains("分类"))throw new Exception("这轮没有明确改变分类标签，原标签先保留。");String mode;JSONArray values;
    if(requested instanceof JSONArray){mode="replace";values=(JSONArray)requested;if(!input.matches("(?s).*(改|换|设为|只留|只要).*"))throw new Exception("标签替换还没核对清楚，原标签先保留。");}
    else if(requested instanceof JSONObject){JSONObject object=(JSONObject)requested;keys(object,new HashSet<>(Arrays.asList("mode","values")));mode=text(object,"mode");values=object.getJSONArray("values");}
    else throw new Exception("新标签没读完整，原标签先保留。");
    if(!Arrays.asList("add","replace","remove").contains(mode)||values.length()>20)throw new Exception("这次标签改动范围太多，原标签先保留。");
    LinkedHashSet<String> names=new LinkedHashSet<>();if(!mode.equals("replace"))for(int i=0;i<array(row,"tags").length();i++)names.add(array(row,"tags").getString(i));
    for(int i=0;i<values.length();i++){Object raw=values.get(i);if(!(raw instanceof String)||((String)raw).trim().isEmpty()||!input.contains((String)raw))throw new Exception("新标签还没能和原话核对上，原标签先保留。");if(mode.equals("remove"))names.remove((String)raw);else names.add((String)raw);}row.put("tags",new JSONArray(names));
  }
  private static ZoneId zone(JSONObject time,JSONObject source){JSONObject anchor=time==null?null:time.optJSONObject("anchor");try{return ZoneId.of(anchor==null?TimeAnchor.zone(source):anchor.optString("zone",TimeAnchor.zone(source)));}catch(Exception e){return ZoneId.of(TimeAnchor.zone(source));}}
  private static long day(JSONObject time){return time==null?0:TimeAnchor.resolved(time)?time.optLong("start"):time.optLong("dateStart");}
  private JSONObject resolveTime(String raw,JSONObject source,JSONObject previous,String provenance)throws Exception {
    return resolveTime(raw,source,previous,provenance,"future");
  }
  private JSONObject resolveTime(String raw,JSONObject source,JSONObject previous,String provenance,String direction)throws Exception {
    boolean hasDate=DATE.matcher(raw).find(),hasClock=CLOCK.matcher(raw).find();JSONObject resolved=AbsoluteDates.temporal(new JSONObject().put("raw",raw).put("source",provenance),source,store,direction);
    if(!provenance.equals("user")){if(!TimeAnchor.resolved(resolved))throw new Exception("资料里的相对时间没有足够的作者日期，这次先没改安排。可以告诉我明确日期。");return resolved;}
    if(resolved.optString("state").equals("unscheduled"))return resolved;
    if(!hasDate&&hasClock){if(day(previous)<=0)throw new Exception("这条事项的日期还没确定，可以一起说日期和时刻，我先不猜。");ZoneId tz=zone(previous,source);LocalDate date=Instant.ofEpochMilli(day(previous)).atZone(tz).toLocalDate();JSONObject clockSource=new JSONObject(AbsoluteDates.frozen(source).toString()).put("capturedZone",tz.getId());resolved=TimeAnchor.resolve(date+"T"+raw,clockSource,false,null,direction).put("source","user").put("raw",raw).put("referenceAnchor",TimeAnchor.source(source)).put("carriedDateFrom",previous.optJSONObject("anchor"));}
    else if(hasDate&&!hasClock&&TimeAnchor.resolved(resolved)&&resolved.optString("precision").equals("day")&&TimeAnchor.resolved(previous)&&previous.optString("precision").equals("minute")){ZonedDateTime old=Instant.ofEpochMilli(previous.getLong("start")).atZone(zone(previous,source));LocalDate date=Instant.ofEpochMilli(resolved.getLong("start")).atZone(ZoneId.of(TimeAnchor.zone(source))).toLocalDate();resolved=TimeAnchor.resolve(date+"T"+String.format(Locale.ROOT,"%02d:%02d",old.getHour(),old.getMinute()),AbsoluteDates.frozen(source),false,null,direction).put("source","user").put("raw",raw).put("carriedClockFrom",previous.optJSONObject("anchor"));}
    if(!TimeAnchor.resolved(resolved)&&!resolved.optBoolean("needsMeridiem")&&!resolved.optString("state").equals("unscheduled"))throw new Exception("这个新时间还没能确定，原来的安排先保留。可以换一种说法。");return resolved;
  }
  private static JSONObject oldAnchor(JSONObject target){JSONObject anchor=target.optJSONObject("time")==null?null:target.optJSONObject("time").optJSONObject("anchor");JSONObject message=new JSONObject();try{message.put("id",anchor==null?target.optString("sourceId"):anchor.optString("messageId")).put("capturedAt",anchor==null?target.optLong("created"):anchor.optLong("capturedAt")).put("capturedZone",anchor==null?TimeZone.getDefault().getID():anchor.optString("zone"));}catch(Exception ignored){}return message;}
  private boolean verifiableReferences(JSONObject row)throws Exception {
    if(row.optString("referenceState").equals("unresolved")||array(row,"unresolvedRelated").length()>0||!row.optString("truthState","current").equals("current")||!row.optString("supersededBy").isEmpty())return false;
    Set<String> ids=new HashSet<>();for(int i=0;i<array(row,"entityIds").length();i++)ids.add(array(row,"entityIds").getString(i));if(!row.optString("subjectId").isEmpty())ids.add(row.getString("subjectId"));
    String body=MemorySchema.normal(row.optString("content"));for(String id:ids){JSONObject entity=store.one("entities",id);if(entity==null)return false;if(entity.optString("kind").equals("self"))continue;String name=MemorySchema.normal(entity.optString("name"));if(name.isEmpty()||!body.contains(name))return false;}return true;
  }
  /** Paused metadata is not evidence: only unique current text slots may be synchronized. */
  private static boolean syncPausedTemporalText(JSONObject row,JSONObject time)throws Exception {
    JSONObject candidate=new JSONObject(row.toString());long instant=day(time);String date=instant>0?when(instant,time).split(" ")[0]:"时间先不定";
    String clock=TimeAnchor.resolved(time)&&time.optString("precision").equals("minute")?when(time.optLong("start"),time).split(" ")[1]:null;
    if(clock==null){Matcher supplied=CLOCK.matcher(time.optString("raw"));if(supplied.find())clock=supplied.group();}
    for(String field:new String[]{"content","title"}){
      String original=row.optString(field);StringBuilder mask=new StringBuilder(original);Matcher hidden=HIDDEN.matcher(original);while(hidden.find())for(int i=hidden.start();i<hidden.end();i++)mask.setCharAt(i,' ');
      Matcher dates=DATE.matcher(mask);int found=0,start=-1,end=-1;while(dates.find()){found++;start=dates.start();end=dates.end();}if(found>1)return false;String value=found==1?original.substring(0,start)+date+original.substring(end):original;
      mask=new StringBuilder(value);hidden=HIDDEN.matcher(value);while(hidden.find())for(int i=hidden.start();i<hidden.end();i++)mask.setCharAt(i,' ');Matcher clocks=CLOCK.matcher(mask);found=0;start=-1;end=-1;while(clocks.find()){found++;start=clocks.start();end=clocks.end();}if(found>1)return false;
      if(found==1&&clock!=null)value=value.substring(0,start)+clock+value.substring(end);
      candidate.put(field,value);
    }
    row.put("content",candidate.getString("content")).put("title",candidate.getString("title"));return true;
  }
  private static void syncTemporalText(JSONObject edited,JSONObject old,JSONObject next)throws Exception {
    JSONObject before=old.optJSONObject("time"),anchor=oldAnchor(old);long oldDay=day(before),newDay=day(next);String date=newDay>0?DateTimeFormatter.ofPattern("yyyy年M月d日",Locale.SIMPLIFIED_CHINESE).format(Instant.ofEpochMilli(newDay).atZone(zone(next,anchor))):"时间先不定";
    for(String field:new String[]{"content","title"}){
      String original=edited.optString(field),masked=original;Matcher hidden=HIDDEN.matcher(original);StringBuilder safe=new StringBuilder(original);while(hidden.find())for(int i=hidden.start();i<hidden.end();i++)safe.setCharAt(i,' ');masked=safe.toString();Matcher dates=DATE.matcher(masked);StringBuilder changed=new StringBuilder();int position=0;
      while(dates.find()){JSONObject found=TimeAnchor.resolve(dates.group(),anchor,false,null,old.optString("kind").equals("event")&&old.optString("eventState").equals("occurred")?"past":"future");if(oldDay>0&&day(found)>0&&Instant.ofEpochMilli(day(found)).atZone(zone(before,anchor)).toLocalDate().equals(Instant.ofEpochMilli(oldDay).atZone(zone(before,anchor)).toLocalDate())){changed.append(original,position,dates.start()).append(date);position=dates.end();}}
      String value=changed.append(original.substring(position)).toString();boolean nextClock=TimeAnchor.resolved(next)&&next.optString("precision").equals("minute");boolean oldClock=TimeAnchor.resolved(before)&&before.optString("precision").equals("minute");
      if(oldClock&&(nextClock||newDay==0)){Matcher clock=CLOCK.matcher(value);ZonedDateTime oldTime=Instant.ofEpochMilli(before.getLong("start")).atZone(zone(before,anchor));String replacement=nextClock?DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(next.getLong("start")).atZone(zone(next,anchor))):"";StringBuilder clocks=new StringBuilder();position=0;while(clock.find()){JSONObject parsed=TimeAnchor.resolve(Instant.ofEpochMilli(oldDay).atZone(zone(before,anchor)).toLocalDate()+"T"+clock.group(),anchor,false,null,"future");if(TimeAnchor.resolved(parsed)&&parsed.optString("precision").equals("minute")&&Instant.ofEpochMilli(parsed.getLong("start")).atZone(zone(before,anchor)).toLocalTime().equals(oldTime.toLocalTime())){clocks.append(value,position,clock.start()).append(replacement);position=clock.end();}}value=clocks.append(value.substring(position)).toString();}
      if(!value.equals(original))edited.put(field,value);
    }
  }
  private static void pauseReminder(JSONObject row){row.remove("remindAt");row.remove("reminderTime");row.remove("reminderConfirmed");row.remove("reminderFired");row.remove("reminderSchedule");try{row.put("reminderRequested",false);}catch(Exception ignored){}}
  private static void moveReminder(JSONObject edited,JSONObject old,JSONObject source,JSONObject time)throws Exception {
    if(time.optString("state").equals("unscheduled")){pauseReminder(edited);return;}long previous=old.optLong("remindAt");if(previous<=0)return;
    if(!TimeAnchor.resolved(time)){edited.remove("remindAt");edited.remove("reminderConfirmed");edited.remove("reminderFired");edited.remove("reminderSchedule");edited.put("reminderTime",time).put("careSourceId",source.getString("id"));return;}
    JSONObject oldTime=old.optJSONObject("time");long moved=0;
    if(TimeAnchor.resolved(oldTime)&&oldTime.optString("precision").equals("minute")&&TimeAnchor.resolved(time)&&time.optString("precision").equals("minute"))moved=time.getLong("start")+(previous-oldTime.getLong("start"));
    else if(day(oldTime)>0&&day(time)>0){ZonedDateTime before=Instant.ofEpochMilli(previous).atZone(zone(oldTime,source));LocalDate oldDate=Instant.ofEpochMilli(day(oldTime)).atZone(zone(oldTime,source)).toLocalDate(),newDate=Instant.ofEpochMilli(day(time)).atZone(zone(time,source)).toLocalDate();LocalDate date=newDate.plusDays(ChronoUnit.DAYS.between(oldDate,before.toLocalDate()));moved=date.atTime(before.toLocalTime()).atZone(zone(time,source)).toInstant().toEpochMilli();}
    edited.remove("remindAt");edited.remove("reminderFired");edited.remove("reminderSchedule");if(moved>0){JSONObject rt=TimeAnchor.resolve(Instant.ofEpochMilli(moved).atZone(zone(time,source)).toOffsetDateTime().toString(),source,false,null,"future").put("source","user").put("raw",time.optString("raw")).put("basis","保留原提醒与事项的间隔，随本轮改期移动");edited.put("reminderTime",rt).put("reminderRequested",true);if(moved>System.currentTimeMillis())edited.put("remindAt",moved);}
  }
  private static String meridiemOnly(String input){String value=input.replaceAll("[\\s，,。！!]+","");return value.matches("上午|早上|早晨|下午|晚上|凌晨")?value:"";}
  private JSONObject continueMeridiem(JSONObject row,JSONObject source,String period)throws Exception {
    JSONObject prior=row.optJSONObject("reminderTime"),anchor=prior==null?null:prior.optJSONObject("anchor");Object h=prior==null?null:prior.opt("candidateHour"),m=prior==null?null:prior.opt("candidateMinute");
    boolean safe=prior!=null&&prior.optString("source").equals("user")&&prior.optBoolean("needsMeridiem")&&prior.optLong("dateStart")>0&&h instanceof Number&&m instanceof Number&&((Number)h).doubleValue()==((Number)h).intValue()&&((Number)m).doubleValue()==((Number)m).intValue()&&((Number)h).intValue()>=1&&((Number)h).intValue()<=12&&((Number)m).intValue()>=0&&((Number)m).intValue()<60&&anchor!=null&&anchor.optLong("capturedAt")>0&&MemoryCare.actionable(row)&&!row.optBoolean("structuredReview")&&!row.optString("referenceState").equals("unresolved")&&array(row,"unresolvedRelated").length()==0&&row.optString("truthState","current").equals("current")&&row.optString("supersededBy").isEmpty();
    if(!safe)throw new Exception("这条提醒原来的日期或钟点还没能核对，先没有猜。可以一起说明确日期和时刻。");
    ZoneId tz;try{tz=ZoneId.of(anchor.getString("zone"));}catch(Exception e){throw new Exception("这条提醒原来的时区没读完整，可以一起说明确日期和时刻。");}
    LocalDate date=Instant.ofEpochMilli(prior.getLong("dateStart")).atZone(tz).toLocalDate();if(date.atStartOfDay(tz).toInstant().toEpochMilli()!=prior.getLong("dateStart"))throw new Exception("这条提醒原来的日期还没能核对，先没有猜。");
    JSONObject clockSource=new JSONObject(AbsoluteDates.frozen(source).toString()).put("capturedZone",tz.getId());JSONObject combined=TimeAnchor.resolve(date+"T"+period+prior.getInt("candidateHour")+"点"+prior.getInt("candidateMinute")+"分",clockSource,false,null,"future");
    if(!TimeAnchor.resolved(combined)&&!combined.optBoolean("needsMeridiem"))throw new Exception("这个提醒时刻还没能确定，原提醒先保留。");
    JSONArray provenance=prior.optJSONArray("provenance")==null?new JSONArray().put(new JSONObject().put("messageId",anchor.optString("messageId")).put("raw",prior.optString("dateRaw",prior.optString("raw"))).put("part","date_and_clock")):new JSONArray(prior.getJSONArray("provenance").toString());provenance.put(new JSONObject().put("messageId",source.getString("id")).put("raw",period).put("part","meridiem"));
    return combined.put("raw",period).put("dateRaw",prior.optString("dateRaw",prior.optString("raw"))).put("source","user").put("anchor",new JSONObject(anchor.toString())).put("referenceAnchor",TimeAnchor.source(AbsoluteDates.frozen(source))).put("referencedMemoryId",row.getString("id")).put("linkedContinuation",true).put("provenance",provenance);
  }
  private void reminder(JSONObject row,JSONObject source,JSONObject request)throws Exception {
    keys(request,new HashSet<>(Arrays.asList("mode","raw","source")));String mode=text(request,"mode"),input=source.optString("content"),period=meridiemOnly(input);if(!input.contains("提醒")&&(period.isEmpty()||!mode.equals("set")))throw new Exception("这轮原话没有要求改变提醒，原提醒先保留。");
    if(mode.equals("cancel")){if(!input.matches("(?s).*(取消|不再|不要|别|停止|不用).*(提醒).*"))throw new Exception("这轮原话没有明确取消提醒，原提醒先保留。");pauseReminder(row);return;}if(!mode.equals("set"))throw new Exception("提醒的改动方式还没核对清楚。");
    String raw=text(request,"raw"),provenance=request.optString("source","user");materialValue(source,raw,provenance,null);JSONObject time;if(!period.isEmpty()){if(!raw.equals(period)||!provenance.equals("user"))throw new Exception("这句补充没能和原话核对上，原提醒先保留。");time=continueMeridiem(row,source,period);}else time=resolveTime(raw,source,row.optJSONObject("time"),provenance);
    row.put("reminderRequested",true).put("careSourceId",source.getString("id")).put("reminderTime",time);row.remove("remindAt");row.remove("reminderConfirmed");row.remove("reminderFired");row.remove("reminderSchedule");row.remove("careDeferred");row.remove("careDeferredSourceId");if(TimeAnchor.resolved(time)&&time.optString("precision").equals("minute")&&time.optString("source","user").equals("user")&&time.optLong("start")>System.currentTimeMillis())row.put("remindAt",time.getLong("start"));
  }
  private static String when(long at,JSONObject time){if(at<=0)return "时间先不定";JSONObject anchor=time==null?null:time.optJSONObject("anchor");ZoneId zone;try{zone=ZoneId.of(anchor==null?TimeZone.getDefault().getID():anchor.optString("zone"));}catch(Exception e){zone=ZoneId.systemDefault();}return DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm",Locale.SIMPLIFIED_CHINESE).format(Instant.ofEpochMilli(at).atZone(zone));}
  private static String acknowledge(JSONObject row,String operation){if(operation.equals("fact"))return "新的说法记好了，之前的事实和这次更正都留下了。";if(operation.equals("status"))return row.optString("eventState").equals("cancelled")?"这件事取消了，原记录和之前的版本都留着。":row.optString("status").equals("completed")?"这件事已经标记完成了。":row.optString("status").equals("archived")?"这条记录归档了，需要时还能找回来。":"这条记录已经放回手边。";if(operation.equals("time")){JSONObject time=row.optJSONObject("time");if(row.optBoolean("structuredReview"))return "这轮新日期已经保存，原文字的时刻或对象还没完整核对。安排仍未确定，旧提醒没有恢复。";if(time!=null&&time.optString("state").equals("unscheduled"))return "时间先不定，原来的提醒也停下了。";boolean minute=TimeAnchor.resolved(time)&&time.optString("precision").equals("minute");return "这条事项的时间改为 "+(minute?when(day(time),time):when(day(time),time).split(" ")[0])+(minute?"。":"，时刻还不确定，我没有猜。");}if(operation.equals("reminder"))return row.optBoolean("reminderRequested")?"提醒的要求记好了。":"这条的提醒停下了，事项本身还留着。";return "这条已经按你的话改好了，原内容和之前的版本都留着。";}
}
