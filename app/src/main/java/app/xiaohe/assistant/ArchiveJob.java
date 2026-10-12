package app.xiaohe.assistant;
import android.app.job.*;
public final class ArchiveJob extends JobService {
  public boolean onStartJob(JobParameters p){new Thread(()->{boolean retry=false;try{Store.get(this).archive(false);}catch(Exception e){retry=true;}jobFinished(p,retry);},"xiaohe-archive").start();return true;}
  public boolean onStopJob(JobParameters p){return true;}
}
