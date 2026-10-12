package app.xiaohe.assistant;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;

public final class Store extends SQLiteOpenHelper {
  private static Store instance;
  public final Context context;
  public static synchronized Store get(Context c) { if(instance==null) instance=new Store(c.getApplicationContext()); return instance; }
  private Store(Context c) { super(c,"xiaohe.db",null,3); context=c; setWriteAheadLoggingEnabled(true); }
  public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, created INTEGER NOT NULL, data TEXT NOT NULL)");
    db.execSQL("CREATE TABLE memories(id TEXT PRIMARY KEY, created INTEGER NOT NULL, status TEXT NOT NULL, data TEXT NOT NULL)");
    db.execSQL("CREATE INDEX memory_status_created ON memories(status,created)");
    db.execSQL("CREATE TABLE files(id TEXT PRIMARY KEY, created INTEGER NOT NULL, data TEXT NOT NULL)");
    MemorySchema.install(db);
  }
  public void onUpgrade(SQLiteDatabase db,int oldV,int newV) {try{if(oldV<2){MemorySchema.install(db);MemorySchema.migrate(db);}if(oldV<3)MemorySchema.migrateCare(db);}catch(JSONException e){throw new SQLiteException("旧记忆迁移未完成，原数据库事务已回滚");} }
  public synchronized void put(String table,JSONObject item) throws JSONException {
    if(!MemorySchema.TABLES.contains(table)) throw new IllegalArgumentException("无效数据类型");
    SQLiteDatabase db=getWritableDatabase();boolean own=!db.inTransaction();if(own)db.beginTransaction();try{
      if(table.equals("messages"))AbsoluteDates.capture(item);
      if(table.equals("memories")){JSONObject old=one(table,item.getString("id"));boolean restored=item.optBoolean("dateNormalizationFromSnapshot");item.remove("dateNormalizationFromSnapshot");if(!restored&&old!=null&&!old.optString("content").equals(item.optString("content"))&&item.optString("dateNormalizationSourceId",item.optString("sourceId")).equals(old.optString("dateNormalizationSourceId",old.optString("sourceId"))))AbsoluteDates.clear(item);String sourceId=item.optString("dateNormalizationSourceId",item.optString("sourceId"));JSONObject source=one("messages",sourceId);if(source!=null)AbsoluteDates.annotate(item,source);if(old!=null&&old.toString().equals(item.toString())){if(own)db.setTransactionSuccessful();return;}item.put("revision",old==null?Math.max(1,item.optInt("revision",1)):old.optInt("revision",1)+1);MemorySchema.normalize(item);}
      ContentValues v=MemorySchema.values(table,item);if(table.equals("versions")&&one(table,item.getString("id"))!=null){if(own)db.setTransactionSuccessful();return;}
      if(db.update(table,v,"id=?",new String[]{item.getString("id")})==0&&db.insertOrThrow(table,null,v)<0)throw new SQLiteException("本机存储没有写入成功，请检查剩余空间");
      if(table.equals("entities"))MemorySchema.indexEntity(db,item);
      if(table.equals("memories")){MemorySchema.index(db,item);MemorySchema.snapshot(db,item,item.optString("changeReason","记录更新"),item.optString("lastChangeSourceId",item.optString("sourceId")));}
      if(own)db.setTransactionSuccessful();
    }finally{if(own)db.endTransaction();}
  }
  public synchronized JSONObject one(String table,String id) throws JSONException {
    if(!MemorySchema.TABLES.contains(table))throw new IllegalArgumentException("无效数据类型");JSONArray rows=jsonRows(getReadableDatabase(),"SELECT data FROM "+table+" WHERE id=?",id);return rows.length()>0?rows.getJSONObject(0):null;
  }
  public synchronized JSONArray all(String table) throws JSONException {
    if(!MemorySchema.TABLES.contains(table))throw new IllegalArgumentException("无效数据类型");return jsonRows(getReadableDatabase(),"SELECT data FROM "+table+" ORDER BY created ASC");
  }
  /** Limit each CursorWindow row, then read the full JSON in bounded chunks when needed. */
  public interface RowVisitor {void accept(JSONObject row)throws Exception;}
  public static JSONArray jsonRows(SQLiteDatabase db,String sql,String... args)throws JSONException {JSONArray out=new JSONArray();try{walkJson(db,sql,row->out.put(row),args);return out;}catch(JSONException e){throw e;}catch(RuntimeException e){throw e;}catch(Exception e){throw new JSONException(e.toString());}}
  public synchronized void eachJson(String table,RowVisitor visitor)throws Exception {if(!MemorySchema.TABLES.contains(table))throw new IllegalArgumentException("无效数据类型");walkJson(getReadableDatabase(),"SELECT data FROM "+table+" ORDER BY created ASC",visitor);}
  private static void walkJson(SQLiteDatabase db,String sql,RowVisitor visitor,String... args)throws Exception {
    final int chunk=131072;Matcher projection=Pattern.compile("^\\s*SELECT\\s+(?:(\\w+)\\.)?data\\s+FROM\\s+",Pattern.CASE_INSENSITIVE).matcher(sql);if(!projection.find())throw new IllegalArgumentException("本机JSON查询需选择data字段");String alias=projection.group(1),prefix=alias==null?"":alias+".",table=null;
    Matcher tables=Pattern.compile("\\b(?:FROM|JOIN)\\s+(messages|memories|files|entities|versions|conflicts)(?:\\s+([a-zA-Z_][a-zA-Z0-9_]*))?",Pattern.CASE_INSENSITIVE).matcher(sql);while(tables.find()){if(alias==null||alias.equalsIgnoreCase(tables.group(1))||alias.equalsIgnoreCase(tables.group(2))){table=tables.group(1).toLowerCase(Locale.ROOT);break;}}if(table==null)throw new IllegalArgumentException("无效数据来源");
    String projected="SELECT substr("+prefix+"data,1,"+chunk+"),length("+prefix+"data),"+prefix+"id FROM "+sql.substring(projection.end());projected=projected.replaceAll("(?i)SELECT\\s+data\\s*,\\s*created\\s+FROM", "SELECT id,data,created FROM");
    try(Cursor c=db.rawQuery(projected,args)){while(c.moveToNext()){String initial=c.getString(0),id=c.getString(2);long length=c.getLong(1);if(length<=chunk){visitor.accept(new JSONObject(initial));continue;}StringBuilder full=new StringBuilder(initial);for(long position=chunk+1;position<=length;position+=chunk){try(Cursor part=db.rawQuery("SELECT substr(data,?,?) FROM "+table+" WHERE id=?",new String[]{""+position,""+chunk,id})){if(!part.moveToFirst())throw new SQLiteException("记录正在变化，请稍后再读");full.append(part.getString(0));}}visitor.accept(new JSONObject(full.toString()));}}
  }
  public synchronized void delete(String table,String id) {if(!MemorySchema.TABLES.contains(table))throw new IllegalArgumentException();SQLiteDatabase db=getWritableDatabase();if(table.equals("memories")){try(Cursor c=db.rawQuery("SELECT rowid FROM memories WHERE id=?",new String[]{id})){if(c.moveToFirst())db.delete("memory_fts","docid=?",new String[]{""+c.getLong(0)});}for(String t:new String[]{"memory_entities","memory_tags","memory_attention"})db.delete(t,"memory_id=?",new String[]{id});}db.delete(table,"id=?",new String[]{id});}
  public File mediaDir() {File f=new File(context.getFilesDir(),"media");f.mkdirs();return f;}
  public File filePath(String id) throws Exception {
    if(!id.matches("[a-zA-Z0-9_-]{8,80}")) throw new IOException("无效附件编号");
    File target=new File(mediaDir(),id).getCanonicalFile();
    if(!target.getParentFile().equals(mediaDir().getCanonicalFile())) throw new IOException("无效附件路径");return target;
  }
  public JSONObject message(String role,String text,JSONArray files,String id) throws Exception {
    return message(role,text,files,id,System.currentTimeMillis(),TimeZone.getDefault().getID());
  }
  public JSONObject message(String role,String text,JSONArray files,String id,long capturedAt,String capturedZone) throws Exception {
    JSONObject existing=one("messages",id);if(existing!=null)return existing;
    JSONObject m=new JSONObject();long now=System.currentTimeMillis();m.put("id",id);m.put("created",now);m.put("capturedAt",capturedAt);m.put("capturedZone",capturedZone);m.put("role",role);m.put("content",text);m.put("files",files==null?new JSONArray():files);if(role.equals("user")){JSONArray recent=rows("SELECT data FROM (SELECT data,created FROM messages ORDER BY created DESC LIMIT 12) ORDER BY created ASC"),ids=new JSONArray();for(int i=0;i<recent.length();i++)ids.put(recent.getJSONObject(i).getString("id"));m.put("contextMessageIds",ids);}put("messages",m);return m;
  }
  public JSONObject reply(String text,String sourceId,String error) throws Exception {
    JSONObject m=message("assistant",text,new JSONArray(),UUID.randomUUID().toString());m.put("sourceId",sourceId);if(error!=null&&!error.isEmpty())m.put("error",error);put("messages",m);return m;
  }
  public JSONArray search(String text,int limit) throws Exception {return new MemoryLedger(this).search(text,limit);}
  public synchronized JSONArray rows(String sql,String... args)throws JSONException {return jsonRows(getReadableDatabase(),sql,args);}
  public synchronized int archive(boolean force) throws Exception {
    android.content.SharedPreferences p=context.getSharedPreferences("maintenance",0);long now=System.currentTimeMillis();long last=p.getLong("lastArchive",0);long week=7L*86400000L;
    if(!force&&now-last<week)return 0;
    JSONArray records=all("memories");int n=0;SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
    try {for(int i=0;i<records.length();i++){JSONObject r=records.getJSONObject(i);long completed=r.optLong("completedAt",0);
      if(r.optString("status").equals("completed")&&!r.optBoolean("longTerm")&&completed>0&&completed<=now-week&&!r.optString("processing").equals("pending")&&MemoryCare.canArchive(r)){r.put("status","archived");r.put("archivedAt",now);r.put("updated",now);r.put("changeReason","每周整理：完成满七天的临时记录");put("memories",r);n++;}}
      db.setTransactionSuccessful();}finally{db.endTransaction();}
    p.edit().putLong("lastArchive",now).putString("report",n>0?"这次替你收好了 "+n+" 条记录。没完成的还留在手边，归档里的也能随时找回。":"这次没有需要归档的内容，手边的事继续替你记着。").apply();return n;
  }
  public synchronized JSONObject bootstrap() throws Exception {
    JSONObject o=new JSONObject();JSONArray recent=rows("SELECT data FROM (SELECT data,created FROM messages ORDER BY created DESC LIMIT 160) ORDER BY created ASC");
    JSONArray files=new JSONArray();eachJson("files",f->{f.remove("text");f.remove("ocrText");files.put(f);});
    o.put("messages",recent);o.put("memories",rows("SELECT data FROM memories ORDER BY created DESC LIMIT 250"));o.put("tasks",rows("SELECT data FROM memories WHERE status IN ('active','completed') AND type IN ('task','event') ORDER BY created DESC LIMIT 200"));o.put("files",files);o.put("settings",new Settings(context).publicValues());
    MemoryLedger ledger=new MemoryLedger(this);o.put("conflicts",ledger.conflicts());o.put("entities",ledger.entities());o.put("memoryPage",ledger.page(new JSONObject()));o.put("attention",ledger.page(new JSONObject().put("status","all").put("attention",true)));try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM memories",null)){c.moveToFirst();o.put("memoryCount",c.getInt(0));}
    o.put("archiveReport",context.getSharedPreferences("maintenance",0).getString("report","我会每周整理一次，归档后的内容也能找回。"));return o;
  }
  public synchronized void pendingRecord(JSONObject m,String reason) throws Exception {
    JSONArray memories=rows("SELECT data FROM memories WHERE created=? LIMIT 100",""+m.getLong("created"));for(int i=0;i<memories.length();i++)if(m.getString("id").equals(memories.getJSONObject(i).optString("sourceId")))return;
    AbsoluteDates.capture(m);put("messages",m);JSONObject r=new JSONObject();r.put("id",UUID.randomUUID().toString());r.put("created",m.getLong("created"));r.put("status","active");r.put("title",m.optString("content").isEmpty()?"收到的素材":shorten(m.optString("content"),38));r.put("content",m.optString("content"));r.put("type","note");r.put("tags",new JSONArray().put("待整理"));r.put("sourceId",m.getString("id"));r.put("sourceAnchor",TimeAnchor.source(AbsoluteDates.frozen(m)));r.put("files",m.optJSONArray("files"));r.put("processing","pending");r.put("processingNote",reason);AbsoluteDates.annotate(r,m);put("memories",r);
  }
  public synchronized void applyAi(JSONObject source,JSONObject ai,JSONArray candidates) throws Exception {new MemoryLedger(this).apply(source,ai,candidates);}
  public static String shorten(String s,int n) {return s.length()>n?s.substring(0,n)+"…":s;}
}
