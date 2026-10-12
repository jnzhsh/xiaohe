package app.xiaohe.tests;

import android.app.*;
import android.os.Bundle;

public final class KeyboardTest extends Instrumentation {
  private String mode="keyboard",phase="after";
  @Override public void onCreate(Bundle arguments){super.onCreate(arguments);if(arguments!=null){mode=arguments.getString("mode","keyboard");phase=arguments.getString("phase","after").replaceAll("[^a-z-]","");}start();}
  @Override public void onStart(){Bundle results=new Bundle();try{
    if(mode.equals("keyboard"))results.putString("measurements",KeyboardViewportSmoke.run(this,phase).toString());
    else if(mode.equals("clipboard"))ClipboardSmoke.run(this);
    else if(mode.equals("organizer-ui"))OrganizerUiSmoke.run(this);
    else throw new IllegalArgumentException("Unknown mode");
    results.putString("status","passed");results.putString("verified",mode);finish(Activity.RESULT_OK,results);
  }catch(Throwable error){results.putString("status","failed");results.putString("error",error.toString());finish(Activity.RESULT_CANCELED,results);}}
}
