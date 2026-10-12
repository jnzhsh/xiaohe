package app.xiaohe.tests;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import app.xiaohe.assistant.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.util.*;
import java.util.zip.*;

/** Synthetic browsing checks. The optional private input is never embedded or printed. */
public final class MemoryTopicsSmoke {
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static final class Fixture implements AutoCloseable {
    final File directory,database;final Context context;final Store store;
    private final Field singleton;private Object previous;private boolean active;
    Fixture(Context original)throws Exception{
      String prefix="topics-"+UUID.randomUUID();directory=new File(original.getCacheDir(),prefix);check(directory.mkdirs(),"Fixture directory");database=new File(directory,"xiaohe.db");
      File files=new File(directory,"files"),cache=new File(directory,"cache");files.mkdirs();cache.mkdirs();
      context=new ContextWrapper(original){
        @Override public Context getApplicationContext(){return this;}
        @Override public File getDatabasePath(String name){return database;}
        @Override public File getFilesDir(){return files;}
        @Override public File getCacheDir(){return cache;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return original.getSharedPreferences(prefix+"-"+name,mode);}
      };
      Constructor<Store> constructor=Store.class.getDeclaredConstructor(Context.class);constructor.setAccessible(true);store=constructor.newInstance(context);
      singleton=Store.class.getDeclaredField("instance");singleton.setAccessible(true);
    }
    void activate()throws Exception{check(!active,"Fixture activates once");previous=singleton.get(null);singleton.set(null,store);active=true;}
    private void remove(File file)throws IOException{if(!file.getCanonicalFile().toPath().startsWith(directory.getCanonicalFile().toPath()))throw new IOException("Fixture path boundary");File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);file.delete();}
    @Override public void close()throws Exception{if(active)singleton.set(null,previous);store.close();SQLiteDatabase.deleteDatabase(database);remove(directory);}
  }
  static final long AT=1893456000000L;
  static final String[] THEMES={"学习","饮食","人情","旅行","运动","工作","生活记录","随手灵感"};
  static JSONArray labels(String... values){JSONArray out=new JSONArray();for(String value:values)out.put(value);return out;}
  static void memory(Store store,String id,JSONArray tags,String status,String title,long created)throws Exception{
    store.put("memories",new JSONObject().put("id",id).put("created",created).put("sourceId","topics-synthetic-source").put("title",title).put("content","合成原话，保留全部线索。").put("tags",tags).put("files",new JSONArray()).put("type","note").put("kind","note").put("status",status));
  }
  static void seed(Store store,boolean large)throws Exception{
    store.put("messages",new JSONObject().put("id","topics-synthetic-source").put("created",AT).put("capturedAt",AT).put("capturedZone","Asia/Shanghai").put("role","user").put("content","用于验证主题浏览的合成资料").put("files",new JSONArray()));
    SQLiteDatabase sql=store.getWritableDatabase();sql.beginTransaction();try{
      int records=large?620:16;
      for(int i=0;i<records;i++){
        JSONArray tags=labels(THEMES[i%THEMES.length]);int details=large?1:5;for(int j=0;j<details;j++)tags.put("细线索"+(i*details+j));
        memory(store,"topics-volume-"+i,tags,"active","合成记忆 "+i,AT+i);
      }
      memory(store,"topics-cross-label",labels("饮食","学习","公开合成"),"active","交叉标签示例",AT+10000);
      memory(store,"topics-card-many",labels("学习","咖啡","豆子","磨豆","水温","比例","香气"),"active","合成多标签示例",AT+20000);
      if(large){
        memory(store,"topics-deep-record",labels("页外主题","深处线索"),"active","铂金琴键",AT-1);
        memory(store,"topics-blank-tags",labels("","\t\u3000","空白后的主题","细标签空白"),"active","空白标签示例",AT-2);
        memory(store,"topics-untagged-row",new JSONArray(),"active","没有标签的原件",AT-3);
        memory(store,"topics-fallback-row",labels("生活记录"),"active","旧版默认主题",AT-4);
        memory(store,"topics-archived-row",labels("归档专用","学习"),"archived","归档示例",AT-5);
        memory(store,"topics-completed-row",labels("完成专用"),"completed","完成示例",AT-6);
        for(int i=0;i<2;i++){JSONObject task=new JSONObject().put("id","topics-task-row-"+i).put("created",AT+30000+i).put("sourceId","topics-synthetic-source").put("title","合成安排 "+i).put("content","合成安排").put("tags",labels("日程","提醒"+i)).put("files",new JSONArray()).put("type",i==0?"task":"event").put("kind","intent").put("status","active");store.put("memories",task);}
        sql.execSQL("INSERT OR IGNORE INTO memory_attention(memory_id) VALUES(?)",new Object[]{"topics-task-row-0"});
        memory(store,"topics-large-body",labels("大资料主题","纸面资料"),"active","大段原件示例",AT-7);
        JSONObject big=store.one("memories","topics-large-body");StringBuilder body=new StringBuilder();for(int i=0;i<400000;i++)body.append("纸面资料");big.put("originalMaterial",body.toString());store.put("memories",big);
      }
      sql.setTransactionSuccessful();
    }finally{sql.endTransaction();}
  }
  static int count(Store store,String table){try(Cursor cursor=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table,null)){cursor.moveToFirst();return cursor.getInt(0);}}
  static byte[] digest(Store store,String table)throws Exception{
    MessageDigest hash=MessageDigest.getInstance("SHA-256");store.eachJson(table,row->{byte[] bytes=row.toString().getBytes(StandardCharsets.UTF_8);hash.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));hash.update((byte)0);hash.update(bytes);});return hash.digest();
  }
  private static byte[] bounded(InputStream input,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){if(out.size()>limit-count)throw new IOException("Private verification input exceeds memory bound");out.write(buffer,0,count);}return out.toByteArray();}
  private static JSONObject originalBackup(byte[] zipBytes)throws Exception{
    try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(zipBytes))){ZipEntry entry;while((entry=zip.getNextEntry())!=null)if(entry.getName().equals("backup.json"))return new JSONObject(new String(bounded(zip,16*1024*1024),StandardCharsets.UTF_8));}
    throw new IOException("Private verification JSON missing");
  }
  private static String canonical(Object value)throws Exception{
    if(value==null||value==JSONObject.NULL)return "null";
    if(value instanceof JSONObject){JSONObject row=(JSONObject)value;List<String> keys=new ArrayList<>();row.keys().forEachRemaining(keys::add);Collections.sort(keys);StringJoiner out=new StringJoiner(",","{","}");for(String key:keys)out.add(JSONObject.quote(key)+":"+canonical(row.get(key)));return out.toString();}
    if(value instanceof JSONArray){JSONArray rows=(JSONArray)value;StringJoiner out=new StringJoiner(",","[","]");for(int i=0;i<rows.length();i++)out.add(canonical(rows.get(i)));return out.toString();}
    if(value instanceof Number)return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
    return value instanceof String?JSONObject.quote((String)value):value.toString();
  }
  private static int originalsPreserved(Store store,JSONObject original)throws Exception{
    JSONArray memories=original.optJSONArray("memories"),versions=original.optJSONArray("versions");
    if(memories!=null)for(int i=0;i<memories.length();i++){JSONObject before=memories.getJSONObject(i),after=store.one("memories",before.getString("id"));check(after!=null,"Private original memory retained");for(String key:new String[]{"tags","title","content"})check(before.has(key)==after.has(key)&&canonical(before.opt(key)).equals(canonical(after.opt(key))),"Private original labels and wording retained");}
    if(versions!=null)for(int i=0;i<versions.length();i++){JSONObject before=versions.getJSONObject(i),after=store.one("versions",before.getString("id"));check(after!=null&&canonical(before).equals(canonical(after)),"Private original version retained without semantic changes");}
    JSONArray files=original.optJSONArray("files");if(files!=null)for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);check(MediaProcessor.hash(store.filePath(file.getString("id"))).equals(file.getString("sha256")),"Private original attachment SHA retained");}
    return versions==null?0:versions.length();
  }
  static int themeCount(JSONObject page,String tag)throws Exception{JSONArray themes=page.getJSONArray("themes");for(int i=0;i<themes.length();i++){JSONObject theme=themes.getJSONObject(i);if(!theme.optBoolean("untagged")&&theme.getString("tag").equals(tag))return theme.getInt("count");}return 0;}
  static void totals(JSONObject page)throws Exception{int total=0;JSONArray themes=page.getJSONArray("themes");for(int i=0;i<themes.length();i++)total+=themes.getJSONObject(i).getInt("count");check(total==page.getInt("scopeTotal"),"Theme counts cover complete facet scope");}
  public static JSONObject run(Context original)throws Exception{
    try(Fixture fixture=new Fixture(original)){
      seed(fixture.store,true);Store store=fixture.store;MemoryLedger ledger=new MemoryLedger(store);byte[] records=digest(store,"memories"),history=digest(store,"versions"),sources=digest(store,"messages");int versions=count(store,"versions");
      JSONObject all=ledger.page(new JSONObject().put("status","all").put("limit",40));totals(all);
      check(all.getInt("total")==631&&all.getJSONArray("rows").length()==40&&all.getBoolean("hasMore"),"Full collection exceeds bounded page");
      check(themeCount(all,"页外主题")==1&&themeCount(all,"大资料主题")==1,"Old themes and large original outside visible page remain counted");
      JSONObject active=ledger.page(new JSONObject());check(active.getInt("total")==630,"Handy scope retains completed and excludes archived");check(themeCount(active,"归档专用")==0&&themeCount(active,"完成专用")==1,"Theme counts follow status semantics");
      JSONObject study=ledger.page(new JSONObject().put("theme","学习"));check(study.getInt("total")==79,"Theme uses first label rather than any matching label");check(themeCount(study,"饮食")==79,"Other themes do not disappear after selecting one");
      JSONObject any=ledger.page(new JSONObject().put("tag","学习"));check(any.getInt("total")==80&&themeCount(any,"饮食")==1,"Legacy any-tag matching includes the secondary label");
      JSONObject cross=ledger.page(new JSONObject().put("theme","饮食").put("tag","学习"));check(cross.getInt("total")==1&&cross.getJSONArray("rows").getJSONObject(0).getString("id").equals("topics-cross-label"),"Theme and detailed tag intersect");
      JSONObject archived=ledger.page(new JSONObject().put("status","archived"));check(archived.getInt("total")==1&&themeCount(archived,"归档专用")==1,"Archive facets stay within archive");
      JSONObject tasks=ledger.page(new JSONObject().put("tasksOnly",true).put("status","active"));check(tasks.getInt("total")==2&&themeCount(tasks,"日程")==2,"Task facets cover only matching tasks");
      JSONObject attention=ledger.page(new JSONObject().put("attention",true));check(attention.getInt("total")==1&&themeCount(attention,"日程")==1,"Attention uses indexed matching scope");
      JSONObject empty=ledger.page(new JSONObject().put("query","zzzzzzzz"));check(empty.getInt("total")==0&&empty.getJSONArray("themes").length()==0&&empty.getJSONArray("tagCounts").length()==0,"No-result search has no stale facets");
      JSONObject deep=ledger.page(new JSONObject().put("query","铂金琴键"));check(deep.getInt("total")==1&&themeCount(deep,"页外主题")==1,"Full indexed search and facets find old record beyond 500 rows");
      check(ledger.page(new JSONObject().put("theme","空白后的主题")).getInt("total")==1,"First nonblank tag handles Unicode whitespace");
      check(ledger.page(new JSONObject().put("themeMissing",true)).getInt("total")==1,"No valid tag has a distinct reachable fallback");
      Set<String> ids=new HashSet<>();for(int offset=0;offset<631;offset+=100){JSONObject page=ledger.page(new JSONObject().put("status","all").put("offset",offset).put("limit",100));check(themeCount(page,"页外主题")==1,"Facets independent of pagination");JSONArray rows=page.getJSONArray("rows");for(int i=0;i<rows.length();i++)check(ids.add(rows.getJSONObject(i).getString("id")),"Pagination cannot duplicate a record");}check(ids.size()==631,"Every record remains reachable");
      check(Arrays.equals(records,digest(store,"memories"))&&Arrays.equals(history,digest(store,"versions"))&&Arrays.equals(sources,digest(store,"messages"))&&versions==count(store,"versions"),"Browsing leaves original records, tags, source and history unchanged");
      JSONObject edited=ledger.edit(new JSONObject().put("id","topics-card-many").put("status","archived"),"");check(edited.getJSONArray("tags").toString().equals(labels("学习","咖啡","豆子","磨豆","水温","比例","香气").toString()),"State update preserves tag order");
      check(themeCount(ledger.page(new JSONObject().put("status","archived")),"学习")==1,"Re-indexed status update retains the first label");
      return new JSONObject().put("records",631).put("themes",all.getJSONArray("themes").length()).put("tags",all.getJSONArray("tagCounts").length()).put("stable",true);
    }
  }
  public static JSONObject runPrivate(Context original,InputStream input)throws Exception{
    try(Fixture fixture=new Fixture(original)){
      // This verifier accepts bounded local test inputs; product Backup retains its own limits.
      byte[] archive=bounded(input,16*1024*1024);JSONObject source=originalBackup(archive);
      fixture.activate();Backup backup=new Backup(fixture.context);backup.importFrom(new ByteArrayInputStream(archive));Store store=fixture.store;MemoryLedger ledger=new MemoryLedger(store);int originalVersions=originalsPreserved(store,source);
      byte[] records=digest(store,"memories"),history=digest(store,"versions"),sources=digest(store,"messages");int memories=count(store,"memories"),versions=count(store,"versions");
      JSONObject all=ledger.page(new JSONObject().put("status","all"));totals(all);check(all.getInt("scopeTotal")==memories,"Private facets cover imported collection");
      JSONArray themes=all.getJSONArray("themes");for(int i=0;i<themes.length();i++){JSONObject theme=themes.getJSONObject(i),page=ledger.page(new JSONObject().put("status","all").put("theme",theme.getString("tag")).put("themeMissing",theme.optBoolean("untagged")));check(page.getInt("total")==theme.getInt("count"),"Private theme count agrees with filter");}
      ByteArrayOutputStream bytes=new ByteArrayOutputStream();backup.exportTo(bytes);check(backup.importFrom(new ByteArrayInputStream(bytes.toByteArray())).getInt("added")==0,"Repeated private backup restores no duplicates");
      check(memories==count(store,"memories")&&versions==count(store,"versions")&&Arrays.equals(records,digest(store,"memories"))&&Arrays.equals(history,digest(store,"versions"))&&Arrays.equals(sources,digest(store,"messages")),"Private browsing and duplicate import leave source, records, tags and history unchanged");
      check(originalsPreserved(store,source)==originalVersions,"Private originals survive browse and repeated import");
      return new JSONObject().put("records",memories).put("versions",versions).put("originalVersions",originalVersions).put("themes",themes.length()).put("tags",all.getJSONArray("tagCounts").length()).put("rawTagsPreserved",true).put("originalVersionsPreserved",true).put("stable",true);
    }
  }
}
