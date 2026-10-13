package app.xiaohe.tests;

import android.app.Instrumentation;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class FeedbackSupport {
  static void check(boolean value,String why){VoiceSupport.check(value,why);}
  static String id(){return UUID.randomUUID().toString();}
  static void proof(Instrumentation test,String mode,JSONObject report)throws Exception{
    File folder=new File(test.getTargetContext().getExternalFilesDir(null),"feedback-proof");check(folder.isDirectory()||folder.mkdirs(),"Create feedback fixture evidence directory");
    try(OutputStream output=new FileOutputStream(new File(folder,mode+".json"))){output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
  }
  static final class Fixture implements AutoCloseable {
    final VoiceSupport.StoreScope scope;
    Fixture(Instrumentation test)throws Exception{scope=new VoiceSupport.StoreScope(test.getTargetContext());}
    @Override public void close()throws Exception{scope.context.getBaseContext().deleteSharedPreferences(scope.context.prefix+"-feedback");scope.close();}
  }
  static JSONObject message(app.xiaohe.assistant.Store db,String role,String text,String sourceId)throws Exception{
    JSONObject message=db.message(role,text,new JSONArray(),id());if(sourceId!=null){message.put("sourceId",sourceId);db.put("messages",message);}return message;
  }
}
