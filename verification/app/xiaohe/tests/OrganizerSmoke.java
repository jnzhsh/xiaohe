package app.xiaohe.tests;
import android.content.*;import android.database.sqlite.*;import app.xiaohe.assistant.*;import org.json.*;import java.io.File;import java.lang.reflect.*;import java.time.*;import java.util.*;

public final class OrganizerSmoke {
  private static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
  public static void run(Context original)throws Exception{
    final String prefix="organizer-"+UUID.randomUUID();File dir=new File(original.getCacheDir(),prefix);dir.mkdirs();File database=new File(dir,"xiaohe.db");
    Context isolated=new ContextWrapper(original){public File getDatabasePath(String name){return database;}public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(prefix+"-"+name,mode);}};
    Constructor<Store> constructor=Store.class.getDeclaredConstructor(Context.class);constructor.setAccessible(true);Store store=constructor.newInstance(isolated);
    try{
      long at=OffsetDateTime.parse("2030-10-12T10:00:00+08:00").toInstant().toEpochMilli();
      JSONObject source=new JSONObject().put("id","legacy-organizer-source").put("role","user").put("content","明天开会").put("created",at).put("capturedAt",at).put("capturedZone","Asia/Shanghai").put("files",new JSONArray());store.put("messages",source);
      JSONObject legacy=new JSONObject().put("id","legacy-organizer-memory").put("sourceId",source.getString("id")).put("created",at).put("title","明天开会").put("content","明天开会").put("kind","intent").put("type","task").put("status","active").put("tags",new JSONArray());
      // Raw old-version rows have no normalized display fields yet.
      store.getWritableDatabase().execSQL("INSERT INTO memories(id,data,created,status,type,kind,truth_state,event_state,event_start) VALUES(?,?,?,?,?,?,?,?,?)",new Object[]{legacy.getString("id"),legacy.toString(),at,"active","task","intent","current","planned",0});
      JSONObject vague=new JSONObject().put("id","vague-organizer-source").put("role","user").put("content","改天请小林吃饭").put("created",at).put("capturedAt",at).put("capturedZone","Asia/Shanghai").put("files",new JSONArray());store.put("messages",vague);
      JSONObject pending=new JSONObject(legacy.toString()).put("id","vague-organizer-memory").put("sourceId",vague.getString("id")).put("title","改天请小林吃饭").put("content","改天请小林吃饭");store.put("memories",pending);
      JSONObject first=MemoryOrganizer.run(store,isolated),converted=store.one("memories",legacy.getString("id"));
      check(first.getInt("datesNormalized")>=1,"Manual organize reports normalized legacy dates");
      check(converted.getString("dateNormalizedContent").contains("2030年10月13日"),"Legacy tomorrow uses October 12 source and not organize clock");
      check(converted.getString("content").equals("明天开会")&&store.one("messages",source.getString("id")).getString("content").equals("明天开会"),"Source and original wording remain");
      check(first.getInt("datesRemaining")>=1&&first.getJSONArray("remainingDates").length()>0,"Unscheduled references have an optional viewing entry");
      check(!store.one("memories",pending.getString("id")).has("dateNormalizedContent"),"Unscheduled dates are not invented");
      check(MemoryOrganizer.run(store,isolated).getInt("datesNormalized")==0,"Repeated organize is stable without new revisions");
      check(!isolated.getSharedPreferences("maintenance",0).getString("report","").isEmpty(),"Result visible in maintenance report");
    }finally{store.close();SQLiteDatabase.deleteDatabase(database);dir.delete();}
  }
}
