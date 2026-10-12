package app.xiaohe.assistant;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import com.k2fsa.sherpa.onnx.*;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Microphone -> private WAV + on-device Chinese text. This class has no network path. */
public final class OfflineSpeech implements AutoCloseable {
  public static final int SAMPLE_RATE=16000, MAX_SECONDS=120;
  public interface Listener {
    void onState(JSONObject state);
    void onPartial(JSONObject text);
    void onResult(JSONObject result);
    void onError(String message);
  }
  public interface PcmSource extends AutoCloseable {
    void start()throws Exception;
    int read(short[] samples)throws Exception;
    void stop();
    void close();
  }
  public interface SourceFactory {PcmSource create()throws Exception;}
  public interface Clock {long now();String zone();}
  public interface Decoder extends AutoCloseable {
    void accept(short[] samples,int count)throws Exception;
    String text()throws Exception;
    String finish()throws Exception;
    void close();
  }
  public interface RecognitionModel extends AutoCloseable {Decoder createDecoder()throws Exception;void close();}
  public interface RecognizerFactory {RecognitionModel load()throws Exception;}

  /** The same bundled decoder is used by capture and Android fixture verification. */
  public static final class SherpaModel implements RecognitionModel {
    private final OnlineRecognizer recognizer;
    public SherpaModel(AssetManager assets)throws IOException {
      String base="asr/zh-14m/";
      // Check bundled bytes before JNI; a missing or corrupt model must become a local error.
      verifyAssets(assets,base);
      OnlineTransducerModelConfig transducer=new OnlineTransducerModelConfig();
      transducer.setEncoder(base+"encoder-epoch-99-avg-1.int8.onnx");
      transducer.setDecoder(base+"decoder-epoch-99-avg-1.onnx");
      transducer.setJoiner(base+"joiner-epoch-99-avg-1.int8.onnx");
      OnlineModelConfig model=new OnlineModelConfig();
      model.setTransducer(transducer);model.setTokens(base+"tokens.txt");model.setNumThreads(2);
      model.setProvider("cpu");model.setModelType("zipformer");model.setDebug(false);
      FeatureConfig feature=new FeatureConfig();feature.setSampleRate(SAMPLE_RATE);feature.setFeatureDim(80);feature.setDither(0);
      OnlineRecognizerConfig config=new OnlineRecognizerConfig();config.setFeatConfig(feature);config.setModelConfig(model);
      config.setEnableEndpoint(false);config.setDecodingMethod("greedy_search");
      recognizer=new OnlineRecognizer(assets,config);
    }
    private static void verifyAssets(AssetManager assets,String base)throws IOException {
      String[][] files={
        {"encoder-epoch-99-avg-1.int8.onnx","1c556ea57cec304e55ec4b72e52c1cc098bb01476ed7d90f3de939fe126487b1"},
        {"decoder-epoch-99-avg-1.onnx","5ee0f03a2768ff1d5c83ef3a493243c7935d316cd41280037b14783a3467cc78"},
        {"joiner-epoch-99-avg-1.int8.onnx","a7cf9d82757bdcf786059454495a9ca95e4bd7347f72473fc08d794475c36169"},
        {"tokens.txt","8b294db9045d6e5f94647f4c1eec1af4da143a75053c399611444b378ff966ac"}};
      for(String[] file:files){
        MessageDigest hash;try{hash=MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IOException("本机暂时不能检查识别资源",e);}
        try(InputStream input=assets.open(base+file[0])){byte[] buffer=new byte[32768];int n;while((n=input.read(buffer))!=-1)hash.update(buffer,0,n);}
        StringBuilder hex=new StringBuilder();for(byte value:hash.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        if(!hex.toString().equals(file[1]))throw new IOException("本机识别资源不完整，请重新安装小禾");
      }
    }
    public Decoder createDecoder(){
      final OnlineStream stream=recognizer.createStream("");
      return new Decoder(){
        private boolean closed,finished;
        public void accept(short[] samples,int count){
          if(closed||finished)throw new IllegalStateException("语音片段已经结束");
          if(count<0||count>samples.length)throw new IllegalArgumentException("无效音频长度");
          float[] values=new float[count];for(int i=0;i<count;i++)values[i]=samples[i]/32768f;
          stream.acceptWaveform(values,SAMPLE_RATE);decode();
        }
        private void decode(){while(recognizer.isReady(stream))recognizer.decode(stream);}
        public String text(){if(closed)return "";return recognizer.getResult(stream).getText().trim();}
        public String finish(){
          if(closed)throw new IllegalStateException("语音片段已经关闭");
          if(!finished){stream.acceptWaveform(new float[SAMPLE_RATE/2],SAMPLE_RATE);stream.inputFinished();decode();finished=true;}
          return text();
        }
        public void close(){if(!closed){closed=true;stream.release();}}
      };
    }
    public void close(){recognizer.release();}
  }

  private static final class Microphone implements PcmSource {
    private final AudioRecord recorder;
    Microphone()throws IOException {
      int min=AudioRecord.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
      if(min<=0)throw new IOException("这部手机暂时不能开始录音");
      recorder=new AudioRecord(MediaRecorder.AudioSource.MIC,SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,Math.max(min,SAMPLE_RATE/2));
      if(recorder.getState()!=AudioRecord.STATE_INITIALIZED){recorder.release();throw new IOException("麦克风暂时不可用");}
    }
    public void start()throws IOException {recorder.startRecording();if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IOException("没有开始录音");}
    public int read(short[] samples){return recorder.read(samples,0,samples.length,AudioRecord.READ_BLOCKING);}
    public void stop(){try{if(recorder.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)recorder.stop();}catch(IllegalStateException ignored){}}
    public void close(){stop();recorder.release();}
  }

  private static final class Session {
    final String id=UUID.randomUUID().toString();
    volatile boolean stop,cancelled;
    volatile PcmSource source;
    volatile long capturedAt;
    volatile String capturedZone="";
  }
  private final Context context;
  private final Listener listener;
  private final SourceFactory sources;
  private final RecognizerFactory models;
  private final Clock clock;
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private volatile Session active;
  private volatile boolean closed;
  private RecognitionModel model;

  public OfflineSpeech(Context context,Listener listener){this(context,listener,Microphone::new,
      ()->new SherpaModel(context.getAssets()),new Clock(){public long now(){return System.currentTimeMillis();}public String zone(){return TimeZone.getDefault().getID();}});}
  public OfflineSpeech(Context context,Listener listener,SourceFactory sources,RecognizerFactory models,Clock clock){
    this.context=context.getApplicationContext();this.listener=listener;this.sources=sources;this.models=models;this.clock=clock;
    worker.execute(()->{
      // Only unfinished capture files older than a day; saved originals live in Store.mediaDir().
      File directory=new File(this.context.getCacheDir(),"offline-voice");File[] stale=directory.listFiles();
      if(stale!=null)for(File file:stale)if(file.isFile()&&file.getName().matches("[a-fA-F0-9-]{36}\\.wav")&&System.currentTimeMillis()-file.lastModified()>86400000L)file.delete();
    });
  }
  public synchronized boolean start(){
    if(closed||active!=null)return false;
    Session session=new Session();active=session;emitState(session,"loading");worker.execute(()->capture(session));return true;
  }
  public boolean isActive(){return active!=null;}
  public void stop(){Session session=active;if(session==null)return;if(session.capturedAt==0){cancel();return;}session.stop=true;stopSource(session);}
  public void cancel(){Session session=active;if(session==null)return;session.cancelled=true;session.stop=true;emitState(session,"cancelling");stopSource(session);}
  private static void stopSource(Session session){PcmSource source=session.source;if(source!=null)source.stop();}
  private JSONObject sessionJson(Session session){try{return new JSONObject().put("sessionId",session.id).put("capturedAt",session.capturedAt).put("capturedZone",session.capturedZone);}catch(Exception e){throw new IllegalStateException(e);}}
  private void emitState(Session session,String state){if(closed)return;try{listener.onState(sessionJson(session).put("state",state));}catch(Exception ignored){}}
  private void capture(Session session){
    File temporary=null;Decoder decoder=null;PcmSource source=null;WavWriter wav=null;long samples=0;String latest="",warning=null;
    try{
      if(model==null)model=models.load();
      if(session.cancelled||closed)return;
      decoder=model.createDecoder();
      if(session.cancelled||closed)return;
      source=sources.create();session.source=source;
      if(session.cancelled||closed)return;
      File directory=new File(context.getCacheDir(),"offline-voice");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("手机暂时没有空间保存录音");
      temporary=new File(directory,session.id+".wav");wav=new WavWriter(temporary);
      source.start();session.capturedAt=clock.now();session.capturedZone=clock.zone();
      emitState(session,"listening");short[] buffer=new short[SAMPLE_RATE/10];long lastPartial=0;
      while(!session.stop&&!closed&&samples<SAMPLE_RATE*(long)MAX_SECONDS){
        int n=source.read(buffer);if(n<0){if(session.stop||closed)break;throw new IOException("麦克风刚刚中断了");}if(n==0)continue;
        n=(int)Math.min(n,SAMPLE_RATE*(long)MAX_SECONDS-samples);wav.write(buffer,n);samples+=n;decoder.accept(buffer,n);
        long now=System.nanoTime();if(now-lastPartial>=250000000L){String text=decoder.text();if(!text.equals(latest)){latest=text;if(!session.cancelled&&!closed)listener.onPartial(sessionJson(session).put("text",text));}lastPartial=now;}
      }
      if(samples>=SAMPLE_RATE*(long)MAX_SECONDS)warning="这段已经有两分钟了，我先替你收下，可以接着说下一段。";
    }catch(Exception|LinkageError e){warning=session.capturedAt==0?"这次没能开始本机识别。可以再试一次，或先打字告诉我。":"录音中断了，已经听到的部分仍会留在手机里。";}
    finally{
      if(source!=null){try{source.close();}catch(RuntimeException e){warning="麦克风刚刚中断了，已经听到的内容仍会留在手机里。";}session.source=null;}
      boolean wavReady=wav!=null;
      if(wav!=null)try{wav.close();}catch(IOException e){wavReady=false;warning="录音没能完整保存，可以再说一次。";}
      if(!session.cancelled&&!closed&&temporary!=null&&samples>0&&wavReady){
        emitState(session,"transcribing");
        try{
          String text=latest;if(decoder!=null)try{text=decoder.finish();}catch(Exception|LinkageError e){warning="这次文字没有完全识别好，原声音留在手机里了。";}
          JSONObject file=new MediaProcessor(context).recordedOffline(temporary,text,session.capturedAt,session.capturedZone);
          if(!session.cancelled&&!closed){
            JSONObject result=sessionJson(session).put("text",text).put("file",file);
            if(warning!=null)result.put("note",warning);
            else if(text.isEmpty())result.put("note","这段没听清楚，声音留在手机里了。可以补几个字，也可以再说一次。");
            listener.onResult(result);
          }else discardFile(file);
        }catch(Exception e){if(!session.cancelled&&!closed)listener.onError("这段没能存好，请检查手机空间后再试。之前写下的内容还在。");}
      }else if(!session.cancelled&&!closed)listener.onError(warning==null?"这段还太短，可以再说一次。":warning);
      if(decoder!=null)try{decoder.close();}catch(RuntimeException|LinkageError ignored){}if(temporary!=null)temporary.delete();
      synchronized(this){if(active==session)active=null;}
      emitState(session,"stopped");
    }
  }
  private void discardFile(JSONObject file){try{Store store=Store.get(context);store.filePath(file.getString("id")).delete();store.delete("files",file.getString("id"));}catch(Exception ignored){}}
  public synchronized void close(){if(closed)return;cancel();closed=true;worker.execute(()->{if(model!=null){model.close();model=null;}});worker.shutdown();}

  private static final class WavWriter implements AutoCloseable {
    private final RandomAccessFile output;private long bytes;private boolean closed;
    WavWriter(File file)throws IOException {output=new RandomAccessFile(file,"rw");output.setLength(0);output.write(new byte[44]);}
    void write(short[] values,int count)throws IOException {byte[] data=new byte[count*2];for(int i=0;i<count;i++){data[i*2]=(byte)values[i];data[i*2+1]=(byte)(values[i]>>>8);}output.write(data);bytes+=data.length;}
    private void integer(long value,int length)throws IOException {for(int i=0;i<length;i++)output.write((int)(value>>>(8*i))&255);}
    public void close()throws IOException {if(closed)return;closed=true;try{output.seek(0);output.writeBytes("RIFF");integer(bytes+36,4);output.writeBytes("WAVEfmt ");integer(16,4);integer(1,2);integer(1,2);integer(SAMPLE_RATE,4);integer(SAMPLE_RATE*2,4);integer(2,2);integer(16,2);output.writeBytes("data");integer(bytes,4);output.getFD().sync();}finally{output.close();}}
  }
}
