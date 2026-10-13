package app.xiaohe.tests;
import android.app.*;
import android.os.Bundle;
import java.io.*;
import org.json.JSONObject;

public final class TopicsTest extends Instrumentation {
  private String mode="backend",phase="after",backupPath="";
  @Override public void onCreate(Bundle arguments){super.onCreate(arguments);if(arguments!=null){mode=arguments.getString("mode","backend");phase=arguments.getString("phase","after");backupPath=arguments.getString("backup","");}start();}
  @Override public void onStart(){Bundle result=new Bundle();try{
    JSONObject measurements;
    if(mode.equals("backend"))measurements=MemoryTopicsSmoke.run(getTargetContext());
    else if(mode.equals("ui"))measurements=MemoryTopicsUiSmoke.run(this,phase);
    else if(mode.equals("private")){if(!backupPath.equals(new File(getTargetContext().getFilesDir(),"xiaohe-private-backup.zip").getAbsolutePath()))throw new IllegalArgumentException("Isolated backup path required");try(InputStream in=new FileInputStream(backupPath)){measurements=MemoryTopicsSmoke.runPrivate(getTargetContext(),in);}}
    else throw new IllegalArgumentException("Unknown mode");
    result.putString("measurements",measurements.toString());result.putString("status","passed");finish(Activity.RESULT_OK,result);
  }catch(Throwable error){result.putString("status","failed");result.putString("error",mode.equals("private")?"Private aggregate verification failed; inspect locally without publishing data":error.toString());finish(Activity.RESULT_CANCELED,result);}}
}
