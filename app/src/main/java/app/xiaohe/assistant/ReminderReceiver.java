package app.xiaohe.assistant;
import android.app.*;
import android.content.*;
import org.json.*;
public final class ReminderReceiver extends BroadcastReceiver {public void onReceive(Context c,Intent i){PendingResult pending=goAsync();new Thread(()->{try{Store db=Store.get(c);JSONObject r=db.one("memories",i.getStringExtra("id"));if(r==null||!r.optString("status").equals("active")||r.optBoolean("reminderFired")||!MemoryCare.canAutoRemind(r))return;if(!Scheduler.notificationsAllowed(c))return;
  Intent open=new Intent(c,MainActivity.class).putExtra("memoryId",r.getString("id"));PendingIntent pi=PendingIntent.getActivity(c,r.getString("id").hashCode(),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  Notification n=new Notification.Builder(c,"reminders").setSmallIcon(R.drawable.ic_notification).setColor(0xff965b3e).setContentTitle(r.optString("title","小禾提醒你")).setContentText(Store.shorten(r.optString("content"),100)).setStyle(new Notification.BigTextStyle().bigText(r.optString("content"))).setContentIntent(pi).setAutoCancel(true).build();c.getSystemService(NotificationManager.class).notify(r.getString("id"),0,n);r.put("reminderFired",true);r.put("changeReason","手机按设定时间发出提醒");db.put("memories",r);
}catch(Exception ignored){}finally{pending.finish();}}).start();}}
