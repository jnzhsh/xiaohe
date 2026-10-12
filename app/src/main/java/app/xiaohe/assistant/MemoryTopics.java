package app.xiaohe.assistant;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.util.*;

/** Read-only browsing projection; tags and version snapshots remain the source of truth.
 * The current index inserts tags in their JSON-array order. Row ids are used only for
 * this display projection, never stored as a business fact. An external table rebuild
 * which changes that order must re-index from the original arrays before using it.
 */
final class MemoryTopics {
  static final String BLANKS=blankCharacters();
  static final String FIRST="(SELECT t.tag FROM memory_tags t WHERE t.rowid=(SELECT MIN(f.rowid) FROM memory_tags f WHERE f.memory_id=m.id AND LENGTH(TRIM(f.tag,?))>0))";
  private static String blankCharacters(){StringBuilder out=new StringBuilder();for(char c=0;c<Character.MAX_VALUE;c++)if(Character.isWhitespace(c)||Character.isSpaceChar(c))out.append(c);return out.toString();}
  static String filter(String where,List<String> params,JSONObject args){
    if(args.optBoolean("themeMissing")){params.add(BLANKS);return where+" AND "+FIRST+" IS NULL";}
    String theme=args.optString("theme");if(theme.isEmpty())return where;
    params.add(BLANKS);params.add(theme);return where+" AND "+FIRST+"=?";
  }
  static JSONArray themes(SQLiteDatabase db,String where,List<String> args)throws JSONException {
    List<String> params=new ArrayList<>();params.add(BLANKS);params.addAll(args);JSONArray result=new JSONArray();
    String sql="SELECT theme,COUNT(*) FROM (SELECT "+FIRST+" AS theme FROM memories m WHERE "+where+") GROUP BY theme ORDER BY COUNT(*) DESC,theme";
    try(Cursor cursor=db.rawQuery(sql,params.toArray(new String[0]))){while(cursor.moveToNext()){
      boolean missing=cursor.isNull(0);String tag=missing?"":cursor.getString(0);
      result.put(new JSONObject().put("key",missing?"untagged":"tag:"+tag).put("tag",tag).put("label",missing?"未分主题":tag).put("untagged",missing).put("count",cursor.getInt(1)));
    }}return result;
  }
  static JSONArray tags(SQLiteDatabase db,String where,List<String> args)throws JSONException {
    List<String> params=new ArrayList<>(args);params.add(BLANKS);JSONArray result=new JSONArray();
    String sql="SELECT mt.tag,COUNT(*) FROM memories m JOIN memory_tags mt ON mt.memory_id=m.id WHERE "+where+" AND LENGTH(TRIM(mt.tag,?))>0 GROUP BY mt.tag ORDER BY COUNT(*) DESC,mt.tag";
    try(Cursor cursor=db.rawQuery(sql,params.toArray(new String[0]))){while(cursor.moveToNext())result.put(new JSONObject().put("tag",cursor.getString(0)).put("count",cursor.getInt(1)));}return result;
  }
}
