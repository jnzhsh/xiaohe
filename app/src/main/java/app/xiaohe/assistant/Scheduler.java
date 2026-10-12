package app.xiaohe.assistant;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import org.json.*;
import java.time.*;

public final class Scheduler {
  public static void init(Context c){if(Build.VERSION.SDK_INT>=26){NotificationManager n=c.getSystemService(NotificationManager.class);n.createNotificationChannel(new NotificationChannel("reminders","小禾的提醒",NotificationManager.IMPORTANCE_DEFAULT));}JobScheduler jobs=c.getSystemService(JobScheduler.class);JobInfo existing=jobs.getPendingJob(1709);if(existing==null)jobs.schedule(new JobInfo.Builder(1709,new ComponentName(c,ArchiveJob.class)).setPersisted(true).setPeriodic(7L*86400000L).build());}
  public static long parse(String s){try{return OffsetDateTime.parse(s).toInstant().toEpochMilli();}catch(Exception e){try{return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();}catch(Exception x){return 0;}}}
  private static PendingIntent intent(Context c,String id){Intent i=new Intent(c,ReminderReceiver.class).setData(Uri.parse("xiaohe://reminder/"+id)).putExtra("id",id);return PendingIntent.getBroadcast(c,0,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
  public static boolean notificationsAllowed(Context c){return (Build.VERSION.SDK_INT<33||c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)&&c.getSystemService(NotificationManager.class).areNotificationsEnabled();}
  public static void set(Context c,JSONObject r)throws Exception {schedule(c,r);}
  public static JSONObject schedule(Context c,JSONObject r)throws Exception {AlarmManager a=c.getSystemService(AlarmManager.class);PendingIntent p=intent(c,r.getString("id"));a.cancel(p);long at=r.optLong("remindAt",0);JSONObject result=new JSONObject().put("state","blocked").put("at",at).put("notificationsAllowed",notificationsAllowed(c));
    if(!r.optString("status","active").equals("active"))return result.put("state","inactive");if(r.optBoolean("reminderFired"))return result.put("state","already_fired");if(at<=0||!MemoryCare.canAutoRemind(r))return result;
    long time=Math.max(at,System.currentTimeMillis()+1500);boolean exact=Build.VERSION.SDK_INT<31||a.canScheduleExactAlarms();try{if(exact)a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,p);else a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,p);return result.put("state","scheduled").put("at",time).put("exact",exact);}catch(SecurityException e){try{a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,p);return result.put("state","scheduled").put("at",time).put("exact",false);}catch(Exception ignored){return result.put("state","failed");}}catch(Exception e){return result.put("state","failed");}
  }
  public static void reschedule(Context c)throws Exception {init(c);JSONArray rs=Store.get(c).rows("SELECT data FROM memories WHERE remind_at>0");for(int i=0;i<rs.length();i++)set(c,rs.getJSONObject(i));}
}
