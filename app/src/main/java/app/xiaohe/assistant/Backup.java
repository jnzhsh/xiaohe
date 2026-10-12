package app.xiaohe.assistant;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.JsonReader;
import android.util.JsonToken;
import org.json.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.util.*;
import java.util.zip.*;

public final class Backup {
  private static final long JSON_LIMIT=64L*1024*1024, TOTAL_LIMIT=8L*1024*1024*1024;
  private static final int ENTRY_LIMIT=200000;
  private static final String[] TABLES={"messages","memories","files","entities","versions","conflicts"};
  private final Store store;private final Context context;
  public Backup(Context c){context=c;store=Store.get(c);}
  private interface RowAction {void accept(JSONObject row)throws Exception;}
  private static void copy(InputStream in,OutputStream out)throws IOException {byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
  private static void entry(ZipOutputStream zip,String name,File file)throws Exception {zip.putNextEntry(new ZipEntry(name));try(InputStream in=new FileInputStream(file)){copy(in,zip);}zip.closeEntry();}
  private static final class LimitedOutput extends FilterOutputStream {
    private long count;
    LimitedOutput(OutputStream out){super(out);}
    @Override public void write(int b)throws IOException {if(++count>JSON_LIMIT)throw new IOException("备份记录超过64MB恢复上限，尚未输出备份；本机完整内容仍保留");out.write(b);}
    @Override public void write(byte[] b,int off,int len)throws IOException {count+=len;if(count>JSON_LIMIT)throw new IOException("备份记录超过64MB恢复上限，尚未输出备份；本机完整内容仍保留");out.write(b,off,len);}
  }
  // Stage and preflight before writing the user's ZIP. No successful export exceeds our restore limits.
  public void exportTo(OutputStream output)throws Exception {synchronized(store){
    File json=File.createTempFile("xiaohe-export-",".json",context.getCacheDir());
    try {
      try(Writer writer=new BufferedWriter(new OutputStreamWriter(new LimitedOutput(new FileOutputStream(json)),StandardCharsets.UTF_8))){
        writer.write("{\"format\":\"app.xiaohe.backup\",\"version\":2,\"created\":"+System.currentTimeMillis());
        for(String table:TABLES){writer.write(",\""+table+"\":[");boolean first=true;try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id FROM "+table+" ORDER BY created ASC,rowid ASC",null)){while(c.moveToNext()){JSONObject row=store.one(table,c.getString(0));if(!first)writer.write(',');writer.write(row.toString());first=false;}}writer.write(']');}
        writer.write(",\"settings\":");writer.write(new Settings(context).publicValues().toString());writer.write('}');
      }
      long total=json.length();int entries=1;
      try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id FROM files ORDER BY created ASC,rowid ASC",null)){while(c.moveToNext()){
        if(++entries>ENTRY_LIMIT)throw new IOException("备份附件条目超过恢复上限，尚未输出备份");JSONObject f=store.one("files",c.getString(0));File path=store.filePath(f.getString("id"));validateAttachment(path,f);total+=path.length();if(total>TOTAL_LIMIT)throw new IOException("备份总内容超过8GB恢复上限，尚未输出备份；本机完整内容仍保留");
      }}
      try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(output))){entry(zip,"backup.json",json);try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id FROM files ORDER BY created ASC,rowid ASC",null)){while(c.moveToNext()){String id=c.getString(0);entry(zip,"attachments/"+id,store.filePath(id));}}}
    }finally{json.delete();}
  }}
  private static final class Imported {
    final File root;final Map<String,Set<String>> ids=new HashMap<>();final Set<String> present=new HashSet<>();int version;
    Imported(File dir){root=dir;for(String table:TABLES)ids.put(table,new HashSet<>());}
    File rows(String table){return new File(root,table+".jsonl");}
    void each(String table,RowAction action)throws Exception {File file=rows(table);if(!file.isFile())return;try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null)action.accept(new JSONObject(line));}}
  }
  private static Object value(JsonReader reader)throws Exception {return value(reader,0);}
  private static Object value(JsonReader reader,int depth)throws Exception {
    if(depth>128)throw new IOException("备份JSON嵌套过深");
    JsonToken token=reader.peek();
    if(token==JsonToken.BEGIN_OBJECT){JSONObject object=new JSONObject();reader.beginObject();Set<String> keys=new HashSet<>();while(reader.hasNext()){String key=reader.nextName();if(!keys.add(key))throw new IOException("备份包含重复字段");object.put(key,value(reader,depth+1));}reader.endObject();return object;}
    if(token==JsonToken.BEGIN_ARRAY){JSONArray array=new JSONArray();reader.beginArray();while(reader.hasNext())array.put(value(reader,depth+1));reader.endArray();return array;}
    if(token==JsonToken.STRING)return reader.nextString();
    if(token==JsonToken.NUMBER){String number=reader.nextString();try{return Long.valueOf(number);}catch(NumberFormatException ignored){return new BigDecimal(number);}}
    if(token==JsonToken.BOOLEAN)return reader.nextBoolean();
    if(token==JsonToken.NULL){reader.nextNull();return JSONObject.NULL;}
    throw new IOException("备份JSON结构无效");
  }
  private static Imported unpackJson(File json,File root)throws Exception {
    Imported data=new Imported(root);String format="";Set<String> keys=new HashSet<>();
    // Keep one row in memory. The archive format stays compatible with v1/v2.
    try(JsonReader reader=new JsonReader(new InputStreamReader(new FileInputStream(json),StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))){
      reader.beginObject();while(reader.hasNext()){
        String key=reader.nextName();if(!keys.add(key))throw new IOException("备份包含重复字段");
        if(data.ids.containsKey(key)){data.present.add(key);reader.beginArray();try(Writer writer=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(data.rows(key)),StandardCharsets.UTF_8))){while(reader.hasNext()){Object parsed=value(reader);if(!(parsed instanceof JSONObject))throw new IOException("备份记录结构无效");JSONObject row=(JSONObject)parsed;String id=row.getString("id");if(!id.matches("[a-zA-Z0-9_-]{8,80}")||!data.ids.get(key).add(id))throw new IOException("备份含有无效或重复编号");writer.write(row.toString());writer.write('\n');}}reader.endArray();}
        else if(key.equals("format"))format=reader.nextString();else if(key.equals("version"))data.version=reader.nextInt();else reader.skipValue();
      }reader.endObject();if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("备份JSON包含额外内容");
    }
    if(!format.equals("app.xiaohe.backup")||data.version<1||data.version>2)throw new IOException("不支持的备份版本");
    for(String table:TABLES)if((data.version==2||Arrays.asList("files","messages","memories").contains(table))&&!data.present.contains(table))throw new IOException("备份缺少完整的"+table+"记录");
    return data;
  }
  public JSONObject importFrom(InputStream input)throws Exception {
    File staging=new File(context.getCacheDir(),"import-"+UUID.randomUUID());if(!staging.mkdirs())throw new IOException("无法准备备份恢复空间");Set<String> seen=new HashSet<>();List<File> copied=new ArrayList<>();boolean committed=false;
    try {
      try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(input))){ZipEntry e;long total=0;int count=0;while((e=zip.getNextEntry())!=null){
        String name=e.getName();if(++count>ENTRY_LIMIT)throw new IOException("备份条目过多");if(!seen.add(name))throw new IOException("备份有重复条目");if(!name.equals("backup.json")&&!name.matches("attachments/[a-zA-Z0-9_-]{8,80}"))throw new IOException("备份含有不安全或不认识的路径");
        File target=new File(staging,name).getCanonicalFile();if(!target.toPath().startsWith(staging.getCanonicalFile().toPath()))throw new IOException("备份路径越界");if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IOException("无法准备备份恢复空间");long entryBytes=0;
        try(OutputStream out=new FileOutputStream(target)){byte[] b=new byte[65536];int n;while((n=zip.read(b))!=-1){entryBytes+=n;total+=n;if(total>TOTAL_LIMIT||name.equals("backup.json")&&entryBytes>JSON_LIMIT)throw new IOException("备份超出可恢复的大小（记录64MB、总内容8GB）");out.write(b,0,n);}}zip.closeEntry();
      }}
      File json=new File(staging,"backup.json");if(!json.isFile())throw new IOException("不是小禾的完整备份");File rowDir=new File(staging,"rows");if(!rowDir.mkdir())throw new IOException("无法准备备份校验空间");Imported data=unpackJson(json,rowDir);
      for(String name:seen)if(name.startsWith("attachments/")&&!data.ids.get("files").contains(name.substring(12)))throw new IOException("备份包含未登记的附件");
      synchronized(store){
        validate(data,staging);
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();int[] added={0};
        try {
          data.each("files",f->{String id=f.getString("id");File src=new File(staging,"attachments/"+id),dst=store.filePath(id);JSONObject existing=store.one("files",id);if(!dst.exists()){copied.add(dst);try(InputStream in=new FileInputStream(src);OutputStream out=new FileOutputStream(dst)){copy(in,out);}}else if(existing==null)throw new IOException("附件路径已占用");if(existing==null)store.put("files",f);});
          data.each("messages",m->{if(store.one("messages",m.getString("id"))==null){if(!m.has("capturedAt"))m.put("capturedAt",m.optLong("created"));if(!m.has("capturedZone"))m.put("capturedZone",TimeZone.getDefault().getID()).put("zoneEstimated",true);store.put("messages",m);}});
          data.each("entities",e->{if(store.one("entities",e.getString("id"))==null)store.put("entities",e);});
          data.each("memories",m->{if(store.one("memories",m.getString("id"))==null){store.put("memories",m);added[0]++;}});
          data.each("versions",v->store.put("versions",v));
          data.each("conflicts",c->{if(store.one("conflicts",c.getString("id"))==null)store.put("conflicts",c);});
          new MemoryLedger(store).auditImported();db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        committed=true;int total;try(Cursor c=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM memories",null)){c.moveToFirst();total=c.getInt(0);}return new JSONObject().put("added",added[0]).put("total",total);
      }
    }catch(OutOfMemoryError e){throw new IOException("备份单条记录超过手机可用内存，恢复未完成；本机内容和原备份仍保留",e);}
    finally {if(!committed)for(File f:copied)f.delete();deleteInside(staging,staging);}
  }
  private void validate(Imported data,File staging)throws Exception {
    Set<String> files=data.ids.get("files"),messages=data.ids.get("messages"),entities=data.ids.get("entities"),memories=data.ids.get("memories");
    data.each("files",f->{String id=f.getString("id");validateAttachment(new File(staging,"attachments/"+id),f);JSONObject existing=store.one("files",id);if(existing!=null){if(!existing.optString("sha256").equals(f.optString("sha256")))throw new IOException("附件编号冲突，未修改本机内容");File local=store.filePath(id);if(local.exists())validateAttachment(local,existing);}});
    data.each("messages",m->{if(!Arrays.asList("user","assistant").contains(m.optString("role")))throw new IOException("无效对话记录");validateFileRefs(m,files);validateRefs(m.optJSONArray("contextMessageIds"),messages,"原时对话");validateRefs(m.optJSONArray("focusEntityIds"),entities,"对话对象");validateRef(m.optString("sourceId"),messages,"对话来源");});
    data.each("entities",e->{validateRefs(e.optJSONArray("sourceIds"),messages,"对象来源");JSONObject old=store.one("entities",e.getString("id"));if(old!=null&&(!MemorySchema.normal(old.optString("name")).equals(MemorySchema.normal(e.optString("name")))||!MemorySchema.normal(old.optString("qualifier")).equals(MemorySchema.normal(e.optString("qualifier")))||!old.optString("kind").equals(e.optString("kind"))))throw new IOException("对象编号对应不同身份，未修改本机内容");});
    data.each("memories",m->validateMemory(m,files,messages,entities));
    data.each("versions",v->{JSONObject snapshot=v.optJSONObject("snapshot");if(snapshot==null||!snapshot.optString("id").equals(v.optString("memoryId"))||!v.optString("memoryId").matches("[a-zA-Z0-9_-]{8,80}"))throw new IOException("无效的历史版本");validateMemory(snapshot,files,messages,entities);validateRef(v.optString("sourceId"),messages,"历史来源");JSONObject old=store.one("versions",v.getString("id"));if(old!=null&&!same(old.getJSONObject("snapshot"),snapshot))throw new IOException("历史版本编号冲突，未修改本机内容");});
    data.each("conflicts",c->{if(!Arrays.asList("open","resolved").contains(c.optString("state"))||c.optString("factKey").isEmpty()||c.optJSONArray("memberIds")==null||c.getJSONArray("memberIds").length()<2)throw new IOException("无效的待确认记录");validateRefs(c.optJSONArray("memberIds"),memories,"冲突中的记忆");validateRef(c.optString("chosenId"),memories,"确认后的记忆");});
  }
  private static void validateMemory(JSONObject m,Set<String> files,Set<String> messages,Set<String> entities)throws Exception {
    if(!Arrays.asList("active","completed","archived").contains(m.optString("status")))throw new IOException("无效记忆状态");validateFileRefs(m,files);validateRef(m.optString("sourceId"),messages,"记忆来源");validateRef(m.optString("lastChangeSourceId"),messages,"更正来源");validateRefs(m.optJSONArray("sourceIds"),messages,"来源");validateRefs(m.optJSONArray("entityIds"),entities,"对象");validateRef(m.optString("subjectId"),entities,"对象");
  }
  private static void validateAttachment(File file,JSONObject f)throws Exception {if(!file.isFile()||file.length()!=f.optLong("size",-1)||!f.optString("sha256").matches("[0-9a-f]{64}")||!MediaProcessor.hash(file).equals(f.optString("sha256")))throw new IOException("附件校验失败："+Store.shorten(f.optString("name"),100));}
  private static boolean same(Object a,Object b)throws Exception {if(a==b)return true;if(a==null||b==null)return false;if(a instanceof JSONObject&&b instanceof JSONObject){JSONObject x=(JSONObject)a,y=(JSONObject)b;if(x.length()!=y.length())return false;Iterator<String> keys=x.keys();while(keys.hasNext()){String key=keys.next();if(!y.has(key)||!same(x.get(key),y.get(key)))return false;}return true;}if(a instanceof JSONArray&&b instanceof JSONArray){JSONArray x=(JSONArray)a,y=(JSONArray)b;if(x.length()!=y.length())return false;for(int i=0;i<x.length();i++)if(!same(x.get(i),y.get(i)))return false;return true;}if(a instanceof Number&&b instanceof Number)return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()))==0;return a.equals(b);}
  private static void validateRef(String id,Set<String> valid,String kind)throws Exception {if(!id.isEmpty()&&!valid.contains(id))throw new IOException("备份缺少引用的"+kind);}
  private static void validateRefs(JSONArray ids,Set<String> valid,String kind)throws Exception {if(ids!=null)for(int i=0;i<ids.length();i++)validateRef(ids.getString(i),valid,kind);}
  private static void validateFileRefs(JSONObject row,Set<String> valid)throws Exception {JSONArray ids=row.optJSONArray("files");if(ids!=null)for(int i=0;i<ids.length();i++)if(!valid.contains(ids.getString(i)))throw new IOException("备份缺少引用的附件");}
  private static void deleteInside(File root,File file)throws Exception {if(!file.getCanonicalFile().toPath().startsWith(root.getCanonicalFile().toPath()))throw new IOException("清理路径越界");if(file.isDirectory()){File[] list=file.listFiles();if(list!=null)for(File child:list)deleteInside(root,child);}file.delete();}
}
