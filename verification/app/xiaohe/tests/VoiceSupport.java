package app.xiaohe.tests;

import android.app.*;
import android.content.*;
import android.content.pm.PackageInfo;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.Build;
import android.webkit.WebView;
import app.xiaohe.assistant.Store;
import org.json.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Public model example audio only. Fixtures never contain user recordings or credentials. */
final class VoiceSupport {
  static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
  static String normalize(String value){return value.replaceAll("[\\p{P}\\s]","");}
  static String evaluate(Instrumentation test,WebView web,String script)throws Exception{
    CountDownLatch done=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>();
    test.runOnMainSync(()->web.evaluateJavascript(script,result->{value.set(result);done.countDown();}));
    check(done.await(10,TimeUnit.SECONDS),"WebView callback timed out");return value.get();
  }
  static boolean waitFor(Instrumentation test,WebView web,String expression)throws Exception{
    for(int i=0;i<100;i++){if("true".equals(evaluate(test,web,expression)))return true;Thread.sleep(100);}return false;
  }
  static WebView web(Activity activity)throws Exception{Field field=activity.getClass().getDeclaredField("web");field.setAccessible(true);return (WebView)field.get(activity);}
  static String hash(InputStream source)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=source){byte[] buf=new byte[32768];int length;while((length=input.read(buf))!=-1)digest.update(buf,0,length);}StringBuilder out=new StringBuilder();for(byte value:digest.digest())out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();}
  static JSONObject environment(Instrumentation test,String sourceSha)throws Exception{
    Context context=test.getTargetContext();PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);
    return new JSONObject().put("sourceSha",sourceSha).put("appVersion",info.versionName).put("appApkSha256",hash(new FileInputStream(info.applicationInfo.sourceDir))).put("api",Build.VERSION.SDK_INT).put("abis",new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS))).put("device",Build.MODEL).put("testData","public model examples and synthetic drafts; no personal data");
  }
  static void proof(Instrumentation test,String mode,JSONObject report)throws Exception{
    File directory=new File(test.getTargetContext().getExternalFilesDir(null),"voice-proof");check(directory.isDirectory()||directory.mkdirs(),"Create voice proof directory");
    try(OutputStream out=new FileOutputStream(new File(directory,mode+".json"))){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
  }
  static void screenshot(Instrumentation test,String name)throws Exception{
    // evaluateJavascript returns before WebView's next compositor frame. Wait
    // for the already asserted UI to paint rather than saving the previous frame.
    test.waitForIdleSync();Thread.sleep(400);
    File directory=new File(test.getTargetContext().getExternalFilesDir(null),"voice-proof");directory.mkdirs();Bitmap bitmap=test.getUiAutomation().takeScreenshot();check(bitmap!=null,"Screenshot captured");
    try(OutputStream out=new FileOutputStream(new File(directory,name+".png"))){check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out),"Screenshot encoded");}finally{bitmap.recycle();}
  }
  static final class Wave {
    final int sampleRate;final short[] samples;
    Wave(int rate,short[] values){sampleRate=rate;samples=values;}
    short[] at16k(){if(sampleRate==16000)return samples;if(sampleRate!=8000)throw new IllegalArgumentException("Unsupported public fixture rate");short[] out=new short[samples.length*2];for(int i=0;i<samples.length;i++){out[i*2]=samples[i];out[i*2+1]=(short)(((int)samples[i]+samples[Math.min(i+1,samples.length-1)])/2);}return out;}
    static int leInt(byte[] value,int offset){return (value[offset]&255)|((value[offset+1]&255)<<8)|((value[offset+2]&255)<<16)|((value[offset+3]&255)<<24);}
    static int leShort(byte[] value,int offset){return (value[offset]&255)|((value[offset+1]&255)<<8);}
    static Wave read(InputStream source)throws Exception{
      ByteArrayOutputStream result=new ByteArrayOutputStream();try(InputStream input=source){byte[] buf=new byte[4096];int n;while((n=input.read(buf))!=-1)result.write(buf,0,n);}byte[] bytes=result.toByteArray();
      check(bytes.length>=44&&new String(bytes,0,4,StandardCharsets.US_ASCII).equals("RIFF")&&new String(bytes,8,4,StandardCharsets.US_ASCII).equals("WAVE"),"Public fixture has a WAV header");
      int rate=0,channels=0,bits=0,format=0,dataOffset=-1,dataSize=0;
      for(int offset=12;offset+8<=bytes.length;){int size=leInt(bytes,offset+4);check(size>=0&&offset+8L+size<=bytes.length,"Bounded WAV chunk");String id=new String(bytes,offset,4,StandardCharsets.US_ASCII);if(id.equals("fmt ")){check(size>=16,"PCM format chunk");format=leShort(bytes,offset+8);channels=leShort(bytes,offset+10);rate=leInt(bytes,offset+12);bits=leShort(bytes,offset+22);}if(id.equals("data")){dataOffset=offset+8;dataSize=size;}offset+=8+size+(size&1);}
      check(format==1&&channels==1&&bits==16&&dataOffset>=0&&dataSize%2==0,"Mono PCM16 fixture");short[] values=new short[dataSize/2];for(int i=0;i<values.length;i++)values[i]=(short)leShort(bytes,dataOffset+i*2);return new Wave(rate,values);
    }
  }
  static final class FixtureContext extends ContextWrapper {
    final File root;final String prefix;
    FixtureContext(Context base)throws Exception{super(base);prefix="voice-fixture-"+UUID.randomUUID();root=new File(base.getCacheDir(),prefix);check(root.mkdirs(),"Create isolated voice fixture");}
    @Override public Context getApplicationContext(){return this;}
    private File dir(String name){File value=new File(root,name);value.mkdirs();return value;}
    @Override public File getCacheDir(){return dir("cache");}
    @Override public File getFilesDir(){return dir("files");}
    @Override public File getDatabasePath(String name){return new File(dir("databases"),name);}
    @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory);}
    @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,handler);}
    @Override public boolean deleteDatabase(String name){return SQLiteDatabase.deleteDatabase(getDatabasePath(name));}
    @Override public SharedPreferences getSharedPreferences(String name,int mode){return getBaseContext().getSharedPreferences(prefix+"-"+name,mode);}
  }
  static final class StoreScope implements AutoCloseable {
    final FixtureContext context;final Store db;final Field singleton;final Object previous;
    StoreScope(Context base)throws Exception{context=new FixtureContext(base);singleton=Store.class.getDeclaredField("instance");singleton.setAccessible(true);previous=singleton.get(null);singleton.set(null,null);db=Store.get(context);}
    @Override public void close()throws Exception{singleton.set(null,previous);db.close();context.getBaseContext().deleteSharedPreferences(context.prefix+"-settings");context.getBaseContext().deleteSharedPreferences(context.prefix+"-maintenance");cleanup(context.root,context.root);}
    private static void cleanup(File root,File target)throws Exception{File safe=root.getCanonicalFile(),file=target.getCanonicalFile();check(file.equals(safe)||file.toPath().startsWith(safe.toPath()),"Cleanup stays inside its own test fixture");if(file.isDirectory()){File[] values=file.listFiles();if(values!=null)for(File child:values)cleanup(safe,child);}check(file.delete()||!file.exists(),"Remove only an isolated test file");}
  }
}
