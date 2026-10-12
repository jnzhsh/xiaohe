package app.xiaohe.assistant;
import android.content.*;
public final class BootReceiver extends BroadcastReceiver {public void onReceive(Context c,Intent i){PendingResult p=goAsync();new Thread(()->{try{Scheduler.reschedule(c);}catch(Exception ignored){}finally{p.finish();}}).start();}}
