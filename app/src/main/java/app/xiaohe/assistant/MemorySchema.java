package app.xiaohe.assistant;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.util.*;

public final class MemorySchema {
  public static final Set<String> TABLES=new HashSet<>(Arrays.asList("messages","memories","files","entities","versions","conflicts"));
  public static void install(SQLiteDatabase db){
    db.execSQL("CREATE INDEX message_created ON messages(created)");
    for(String column:new String[]{"subject_id TEXT NOT NULL DEFAULT ''","predicate TEXT NOT NULL DEFAULT ''","scope_key TEXT NOT NULL DEFAULT ''","truth_state TEXT NOT NULL DEFAULT 'current'","event_start INTEGER NOT NULL DEFAULT 0","valid_from INTEGER NOT NULL DEFAULT 0","valid_to INTEGER NOT NULL DEFAULT 0","fact_key TEXT NOT NULL DEFAULT ''","kind TEXT NOT NULL DEFAULT 'legacy'","event_state TEXT NOT NULL DEFAULT ''","remind_at INTEGER NOT NULL DEFAULT 0","updated INTEGER NOT NULL DEFAULT 0","fingerprint TEXT NOT NULL DEFAULT ''","type TEXT NOT NULL DEFAULT 'note'"})db.execSQL("ALTER TABLE memories ADD COLUMN "+column);
    db.execSQL("ALTER TABLE files ADD COLUMN sha256 TEXT NOT NULL DEFAULT ''");
    db.execSQL("CREATE INDEX file_sha ON files(sha256)");
    db.execSQL("CREATE INDEX memory_fact ON memories(fact_key,truth_state,valid_from)");
    db.execSQL("CREATE INDEX memory_subject_time ON memories(subject_id,event_start)");
    db.execSQL("CREATE INDEX memory_truth_time ON memories(truth_state,event_start)");
    db.execSQL("CREATE INDEX memory_event ON memories(kind,event_state,event_start)");
    db.execSQL("CREATE INDEX memory_reminder ON memories(remind_at)");
    db.execSQL("CREATE INDEX memory_fingerprint ON memories(fingerprint)");
    db.execSQL("CREATE INDEX memory_type_status ON memories(type,status,created)");
    db.execSQL("CREATE TABLE entities(id TEXT PRIMARY KEY,created INTEGER NOT NULL,name_norm TEXT NOT NULL,qualifier TEXT NOT NULL,data TEXT NOT NULL)");
    db.execSQL("CREATE INDEX entity_name ON entities(name_norm,qualifier)");
    db.execSQL("CREATE TABLE entity_aliases(entity_id TEXT NOT NULL,alias TEXT NOT NULL,PRIMARY KEY(entity_id,alias))");
    db.execSQL("CREATE INDEX alias_entity ON entity_aliases(alias,entity_id)");
    db.execSQL("CREATE TABLE versions(id TEXT PRIMARY KEY,created INTEGER NOT NULL,memory_id TEXT NOT NULL,data TEXT NOT NULL)");
    db.execSQL("CREATE INDEX version_memory_time ON versions(memory_id,created)");
    db.execSQL("CREATE TABLE conflicts(id TEXT PRIMARY KEY,created INTEGER NOT NULL,fact_key TEXT NOT NULL,state TEXT NOT NULL,data TEXT NOT NULL)");
    db.execSQL("CREATE INDEX conflict_key_state ON conflicts(fact_key,state)");
    db.execSQL("CREATE TABLE memory_entities(memory_id TEXT NOT NULL,entity_id TEXT NOT NULL,PRIMARY KEY(memory_id,entity_id))");
    db.execSQL("CREATE INDEX entity_memory ON memory_entities(entity_id,memory_id)");
    db.execSQL("CREATE TABLE memory_tags(memory_id TEXT NOT NULL,tag TEXT NOT NULL,PRIMARY KEY(memory_id,tag))");
    db.execSQL("CREATE INDEX tag_memory ON memory_tags(tag,memory_id)");
    db.execSQL("CREATE TABLE memory_attention(memory_id TEXT PRIMARY KEY)");
    db.execSQL("CREATE VIRTUAL TABLE memory_fts USING fts4(memory_id,terms,notindexed=memory_id,tokenize=unicode61)");
  }
  public static String normal(String value){if(value==null)return "";String s=java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);StringBuilder out=new StringBuilder(s.length());for(int i=0;i<s.length();i++){char c=s.charAt(i);if(!Character.isWhitespace(c)&&!Character.isSpaceChar(c))out.append(c);}return out.toString();}
  public static String semantic(String value){return value==null?"":java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFC).replace("\r\n","\n").trim();}
  private static final char[] HEX="0123456789abcdef".toCharArray();
  private static final java.util.regex.Pattern WORDS=java.util.regex.Pattern.compile("[a-z0-9]{2,48}");
  private static void hex(StringBuilder out,char c){out.append(HEX[(c>>>12)&15]).append(HEX[(c>>>8)&15]).append(HEX[(c>>>4)&15]).append(HEX[c&15]);}
  public static String terms(String value){String s=normal(value);LinkedHashSet<String> out=new LinkedHashSet<>();java.util.regex.Matcher words=WORDS.matcher(semantic(value).toLowerCase(Locale.ROOT));while(words.find()){String word=words.group();StringBuilder token=new StringBuilder(1+word.length()*4).append('w');for(int i=0;i<word.length();i++)hex(token,word.charAt(i));out.add(token.toString());}for(int i=0;i+1<s.length();i++){StringBuilder token=new StringBuilder(9).append('g');hex(token,s.charAt(i));hex(token,s.charAt(i+1));out.add(token.toString());}return String.join(" ",out);}
  private static String framed(String value){return value.length()+":"+value;}
  public static String scope(Object scope){if(scope instanceof JSONObject){JSONObject obj=(JSONObject)scope;List<String> keys=new ArrayList<>();obj.keys().forEachRemaining(keys::add);keys.sort(Comparator.comparing(MemorySchema::normal).thenComparing(Comparator.naturalOrder()));StringBuilder b=new StringBuilder();for(String key:keys)b.append(framed(normal(key))).append(framed(semantic(obj.optString(key))));return b.toString();}return semantic(scope==null?"":scope.toString());}
  public static void normalize(JSONObject r)throws JSONException {
    if(!r.has("truthState"))r.put("truthState","current");if(!r.has("kind"))r.put("kind","legacy");if(!r.has("sourceIds"))r.put("sourceIds",new JSONArray().put(r.optString("sourceId")));
    if(!r.has("referenceState"))r.put("referenceState",r.optString("subjectId").isEmpty()?"none":"resolved");
    if(!r.has("entityIds"))r.put("entityIds",new JSONArray());JSONArray ids=r.getJSONArray("entityIds");boolean found=false;for(int i=0;i<ids.length();i++)if(ids.optString(i).equals(r.optString("subjectId")))found=true;if(!found&&!r.optString("subjectId").isEmpty())ids.put(r.optString("subjectId"));
    r.put("scopeKey",scope(r.opt("scope")));String subject=r.optString("subjectId"),predicate=normal(r.optString("predicate"));r.put("predicate",predicate);r.put("factKey",r.optString("kind").equals("fact")&&!subject.isEmpty()&&!predicate.isEmpty()?subject+"|"+predicate+"|"+r.getString("scopeKey"):"");
    r.put("care",MemoryCare.inspect(r));
  }
  public static String fingerprint(JSONObject r){String value=framed(r.optString("kind"))+framed(semantic(r.optString("content")))+framed(r.optString("subjectId"))+framed(scope(r.opt("scope")));try{byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder out=new StringBuilder(64);for(byte b:bytes)out.append(HEX[(b&255)>>>4]).append(HEX[b&15]);return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
  static String searchable(JSONObject r){return r.optString("title")+" "+r.optString("content")+" "+r.optString("dateNormalizedTitle")+" "+r.optString("dateNormalizedContent")+" "+(r.optJSONArray("tags")==null?"":r.optJSONArray("tags").toString())+" "+r.optString("subjectLabel")+" "+r.optString("value")+" "+r.optString("predicate");}
  public static ContentValues values(String table,JSONObject r)throws JSONException {
    ContentValues v=new ContentValues();v.put("id",r.getString("id"));v.put("created",r.optLong("created",System.currentTimeMillis()));
    if(table.equals("memories")){normalize(r);v.put("status",r.optString("status","active"));v.put("subject_id",r.optString("subjectId"));v.put("predicate",r.optString("predicate"));v.put("scope_key",r.optString("scopeKey"));v.put("truth_state",r.optString("truthState","current"));JSONObject time=r.optJSONObject("time");v.put("event_start",time==null?0:time.optLong("start",0));v.put("valid_from",r.optLong("validFrom",0));v.put("valid_to",r.optLong("validTo",0));v.put("fact_key",r.optString("factKey"));v.put("type",r.optString("type","note"));v.put("fingerprint",fingerprint(r));v.put("kind",r.optString("kind"));v.put("event_state",r.optString("eventState"));v.put("remind_at",r.optLong("remindAt",Scheduler.parse(r.optString("remindAt"))));v.put("updated",r.optLong("updated",r.optLong("created")));}
    if(table.equals("files"))v.put("sha256",r.optString("sha256"));
    if(table.equals("entities")){v.put("name_norm",normal(r.optString("name")));v.put("qualifier",normal(r.optString("qualifier")));}
    if(table.equals("versions"))v.put("memory_id",r.getString("memoryId"));
    if(table.equals("conflicts")){v.put("fact_key",r.getString("factKey"));v.put("state",r.optString("state","open"));}
    v.put("data",r.toString());return v;
  }
  public static void index(SQLiteDatabase db,JSONObject r)throws JSONException {
    String id=r.getString("id");long docid;try(Cursor c=db.rawQuery("SELECT rowid FROM memories WHERE id=?",new String[]{id})){if(!c.moveToFirst())throw new IllegalStateException();docid=c.getLong(0);}db.delete("memory_fts","docid=?",new String[]{""+docid});ContentValues fts=new ContentValues();fts.put("docid",docid);fts.put("memory_id",id);fts.put("terms",terms(searchable(r)));db.insertOrThrow("memory_fts",null,fts);
    db.delete("memory_entities","memory_id=?",new String[]{id});JSONArray entities=r.optJSONArray("entityIds");if(entities!=null)for(int i=0;i<entities.length();i++)if(!entities.optString(i).isEmpty()){ContentValues link=new ContentValues();link.put("memory_id",id);link.put("entity_id",entities.getString(i));db.insertWithOnConflict("memory_entities",null,link,SQLiteDatabase.CONFLICT_IGNORE);}
    db.delete("memory_tags","memory_id=?",new String[]{id});JSONArray tags=r.optJSONArray("tags");if(tags!=null)for(int i=0;i<tags.length();i++){ContentValues tag=new ContentValues();tag.put("memory_id",id);tag.put("tag",tags.optString(i));db.insertWithOnConflict("memory_tags",null,tag,SQLiteDatabase.CONFLICT_IGNORE);}
    db.delete("memory_attention","memory_id=?",new String[]{id});if(MemoryCare.actionable(r)){ContentValues attention=new ContentValues();attention.put("memory_id",id);db.insertOrThrow("memory_attention",null,attention);}
  }
  public static void indexEntity(SQLiteDatabase db,JSONObject r)throws JSONException {String id=r.getString("id");db.delete("entity_aliases","entity_id=?",new String[]{id});JSONArray names=r.optJSONArray("aliases");if(names==null)names=new JSONArray();names.put(r.optString("name"));for(int i=0;i<names.length();i++){String name=normal(names.optString(i));if(name.isEmpty())continue;ContentValues alias=new ContentValues();alias.put("entity_id",id);alias.put("alias",name);db.insertWithOnConflict("entity_aliases",null,alias,SQLiteDatabase.CONFLICT_IGNORE);}}
  public static void snapshot(SQLiteDatabase db,JSONObject r,String reason,String source)throws JSONException {
    JSONObject row=new JSONObject().put("id",UUID.randomUUID().toString()).put("created",System.currentTimeMillis()).put("memoryId",r.getString("id")).put("sourceId",source).put("reason",reason).put("snapshot",new JSONObject(r.toString()));db.insertOrThrow("versions",null,values("versions",row));
  }
  public static void migrate(SQLiteDatabase db)throws JSONException {
    JSONArray messages=Store.jsonRows(db,"SELECT data FROM messages");for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);if(!m.has("capturedAt"))m.put("capturedAt",m.optLong("created"));if(!m.has("capturedZone"))m.put("capturedZone",TimeZone.getDefault().getID()).put("zoneEstimated",true);db.update("messages",values("messages",m),"id=?",new String[]{m.getString("id")});}
    JSONArray memories=Store.jsonRows(db,"SELECT data FROM memories");for(int i=0;i<memories.length();i++){JSONObject r=memories.getJSONObject(i);r.put("schemaOrigin",1);r.put("revision",1);normalize(r);db.update("memories",values("memories",r),"id=?",new String[]{r.getString("id")});index(db,r);snapshot(db,r,"保留1.0原有记录；未重新推断人物与时间",r.optString("sourceId"));}
    JSONArray files=Store.jsonRows(db,"SELECT data FROM files");for(int i=0;i<files.length();i++){JSONObject r=files.getJSONObject(i);db.update("files",values("files",r),"id=?",new String[]{r.getString("id")});}
  }
  public static void migrateCare(SQLiteDatabase db)throws JSONException {
    JSONArray rows=Store.jsonRows(db,"SELECT data FROM memories");for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);String kind=r.optString("kind"),direction=kind.equals("intent")||kind.equals("event")&&!r.optString("eventState").equals("occurred")?"future":kind.equals("event")?"past":"";JSONObject source=null;JSONArray sources=Store.jsonRows(db,"SELECT data FROM messages WHERE id=?",r.optString("sourceId"));if(sources.length()>0)source=sources.getJSONObject(0);
      if(source!=null){for(String key:new String[]{"time","reminderTime"}){JSONObject old=r.optJSONObject(key);if(old==null||TimeAnchor.resolved(old)||!old.optString("source","user").equals("user")||old.optString("raw").isEmpty()||!source.optString("content").replaceAll("\\s+","").contains(old.optString("raw").replaceAll("\\s+","")))continue;String d=key.equals("reminderTime")?"future":direction;if(d.isEmpty())continue;JSONObject updated=TimeAnchor.resolve(old.optString("raw"),source,false,null,d).put("source","user");r.put(key,updated);}
        JSONArray ids=source.optJSONArray("memoryIds");if(!r.has("reminderRequested")&&MemoryCare.requestsReminder(source.optString("content"))&&(r.optJSONObject("reminderTime")!=null||MemoryCare.requestsReminder(r.optString("content"))||ids!=null&&ids.length()==1&&ids.optString(0).equals(r.optString("id"))))r.put("reminderRequested",true).put("careSourceId",source.optString("id"));}
      db.update("memories",values("memories",r),"id=?",new String[]{r.getString("id")});index(db,r);
    }
  }
}
