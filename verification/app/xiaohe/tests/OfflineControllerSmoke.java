package app.xiaohe.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.content.pm.PackageManager;
import app.xiaohe.assistant.*;
import org.json.*;
import java.io.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Production capture controller, with separate deterministic PCM and actual microphone modes. */
public final class OfflineControllerSmoke {
  private static final long BEFORE_MIDNIGHT=OffsetDateTime.parse("2033-03-28T23:59:30+08:00").toInstant().toEpochMilli();
  private static final String ZONE="Asia/Shanghai";
  private static final class Probe implements OfflineSpeech.Listener {
    final CountDownLatch listening=new CountDownLatch(1),stopped=new CountDownLatch(1);
    final AtomicReference<JSONObject> result=new AtomicReference<>();final AtomicReference<String> error=new AtomicReference<>();
    final List<String> states=Collections.synchronizedList(new ArrayList<>());
    public void onState(JSONObject value){String state=value.optString("state");states.add(state);if(state.equals("listening"))listening.countDown();if(state.equals("stopped"))stopped.countDown();}
    public void onPartial(JSONObject value){}
    public void onResult(JSONObject value){result.set(value);}
    public void onError(String message){error.set(message);}
    void awaitListening()throws Exception{VoiceSupport.check(listening.await(30,TimeUnit.SECONDS),"Capture reached listening, error="+error.get());}
    void awaitStopped()throws Exception{VoiceSupport.check(stopped.await(30,TimeUnit.SECONDS),"Capture cleanup reached stopped");}
    JSONArray states(){synchronized(states){return new JSONArray(states);}}
  }
  private static final class MutableClock implements OfflineSpeech.Clock {
    volatile long now=BEFORE_MIDNIGHT-5000;volatile String zone=ZONE;
    public long now(){return now;}public String zone(){return zone;}
  }
  private static final class ScriptedSource implements OfflineSpeech.PcmSource {
    final short[] values;final MutableClock clock;final CountDownLatch drained=new CountDownLatch(1),reading=new CountDownLatch(1);volatile boolean stopped,closed;int offset;
    ScriptedSource(short[] values,MutableClock clock){this.values=values;this.clock=clock;}
    public void start(){if(clock!=null){clock.now=BEFORE_MIDNIGHT;clock.zone=ZONE;}}
    public int read(short[] buffer)throws Exception{
      reading.countDown();if(stopped)return -1;
      if(offset<values.length){int count=Math.min(buffer.length,values.length-offset);System.arraycopy(values,offset,buffer,0,count);offset+=count;if(clock!=null){clock.now=BEFORE_MIDNIGHT+86400000L;clock.zone="America/Los_Angeles";}if(offset==values.length)drained.countDown();return count;}
      drained.countDown();Thread.sleep(5);return stopped?-1:0;
    }
    public void stop(){stopped=true;}public void close(){stopped=true;closed=true;}
  }
  private static final class FakeModel implements OfflineSpeech.RecognitionModel {
    final AtomicInteger streams=new AtomicInteger(),released=new AtomicInteger();final AtomicBoolean closed=new AtomicBoolean();
    public OfflineSpeech.Decoder createDecoder(){streams.incrementAndGet();return new OfflineSpeech.Decoder(){boolean done;public void accept(short[] samples,int count){}public String text(){return "";}public String finish(){return "";}public void close(){if(!done){done=true;released.incrementAndGet();}}};}
    public void close(){closed.set(true);}
  }
  private static void close(OfflineSpeech controller,FakeModel model)throws Exception{controller.close();for(int i=0;i<200&&!model.closed.get();i++)Thread.sleep(10);VoiceSupport.check(model.closed.get(),"Controller releases model on close");VoiceSupport.check(!controller.start(),"Closed controller cannot capture another session");}
  private static void noTemp(VoiceSupport.FixtureContext context){File[] pending=new File(context.getCacheDir(),"offline-voice").listFiles();VoiceSupport.check(pending==null||pending.length==0,"Completed/cancelled recording leaves no temporary WAV");}
  public static JSONObject run(Instrumentation test)throws Exception{
    JSONObject report=new JSONObject();
    try(VoiceSupport.StoreScope fixture=new VoiceSupport.StoreScope(test.getTargetContext())){
      Store store=fixture.db;
      // Actual model + scripted synthetic spoken tomorrow. The fake clock advances a full day while recording.
      VoiceSupport.Wave wave=VoiceSupport.Wave.read(test.getContext().getAssets().open("tomorrow-zh.wav"));MutableClock clock=new MutableClock();ScriptedSource source=new ScriptedSource(wave.at16k(),clock);Probe result=new Probe();
      OfflineSpeech speech=new OfflineSpeech(fixture.context,result,()->source,()->new OfflineSpeech.SherpaModel(fixture.context.getAssets()),clock);
      try{
        VoiceSupport.check(speech.start(),"First scripted production capture starts");VoiceSupport.check(!speech.start(),"Double start cannot open a second microphone");result.awaitListening();VoiceSupport.check(source.drained.await(30,TimeUnit.SECONDS),"All synthetic PCM reached the real production decoder");speech.stop();result.awaitStopped();
        JSONObject value=result.result.get();VoiceSupport.check(value!=null,"Synthetic spoken recording produced an editable result, error="+result.error.get());
        VoiceSupport.check(value.getString("text").contains("明天")&&value.getString("text").contains("下午三点"),"Local decoded relative date/clock preserved: "+value.getString("text"));
        VoiceSupport.check(value.getLong("capturedAt")==BEFORE_MIDNIGHT&&value.getString("capturedZone").equals(ZONE),"Result uses microphone capture date and zone despite midnight/zone changes during recognition");
        JSONObject file=value.getJSONObject("file");VoiceSupport.check(file.optBoolean("localVoice")&&file.optBoolean("recorded")&&file.getString("mime").equals("audio/wav"),"Recorded origin explicitly marks the WAV as local voice");
        VoiceSupport.check(file.getLong("capturedAt")==BEFORE_MIDNIGHT&&file.getString("capturedZone").equals(ZONE),"Local original metadata preserves source anchor");
        File original=store.filePath(file.getString("id"));VoiceSupport.Wave recorded=VoiceSupport.Wave.read(new FileInputStream(original));VoiceSupport.check(recorded.sampleRate==16000&&Arrays.equals(recorded.samples,wave.at16k()),"Original WAV retains every PCM sample without network conversion");
        VoiceSupport.check(file.getString("sha256").equals(MediaProcessor.hash(original)),"Local WAV checksum matches bytes");VoiceSupport.check(source.closed&&!speech.isActive(),"Microphone source and session released after final decoding");
        VoiceSupport.check(store.all("messages").length()==0&&store.all("memories").length()==0,"Recognition cannot auto-send a message or create an AI memory");
        // Configured upload endpoints must still be skipped for this WAV, including empty local results.
        new Settings(fixture.context).save(new JSONObject().put("asrUrl","http://127.0.0.1:1/transcriptions").put("asrModel","must-not-call"));
        JSONObject prepared=new MediaProcessor(fixture.context).prepare(new JSONArray().put(file.getString("id")));VoiceSupport.check(prepared.getJSONArray("frames").length()==0&&store.one("files",file.getString("id")).getString("text").equals(value.getString("text")),"Local WAV preparation keeps local text and avoids the configured upload fallback");
        JSONObject empty=new JSONObject(file.toString()).put("text","");store.put("files",empty);new MediaProcessor(fixture.context).prepare(new JSONArray().put(file.getString("id")));VoiceSupport.check(store.one("files",file.getString("id")).getString("text").isEmpty(),"An empty local transcript cannot be uploaded as a fallback");
        report.put("scriptedCapture",new JSONObject().put("actualDecoder",true).put("recognizedText",value.getString("text")).put("capturedAt",value.getLong("capturedAt")).put("capturedZone",value.getString("capturedZone")).put("originalPcmEqual",true).put("states",result.states()).put("autoSentMessages",0).put("uploadFallbackSkipped",true));
      }finally{speech.close();}
      noTemp(fixture.context);int fileCount=store.all("files").length();
      // Stopping before any samples have been captured is a friendly error with no retained attachment.
      FakeModel fake=new FakeModel();ScriptedSource empty=new ScriptedSource(new short[0],null);Probe shortProbe=new Probe();speech=new OfflineSpeech(fixture.context,shortProbe,()->empty,()->fake,new MutableClock());
      VoiceSupport.check(speech.start(),"Zero-sample session starts");shortProbe.awaitListening();VoiceSupport.check(empty.reading.await(5,TimeUnit.SECONDS),"Zero-sample capture attempted PCM read");speech.stop();shortProbe.awaitStopped();VoiceSupport.check(shortProbe.result.get()==null&&shortProbe.error.get()!=null&&shortProbe.error.get().contains("太短"),"Zero-sample capture gives a useful short-recording error");VoiceSupport.check(empty.closed&&store.all("files").length()==fileCount,"Short capture releases microphone and adds no file");close(speech,fake);noTemp(fixture.context);report.put("zeroSampleStop","friendly error; no file or message");
      // Cancel while the model is blocked loading: no microphone may start after the load completes.
      CountDownLatch loading=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger creates=new AtomicInteger();FakeModel delayed=new FakeModel();Probe loadingProbe=new Probe();speech=new OfflineSpeech(fixture.context,loadingProbe,()->{creates.incrementAndGet();return new ScriptedSource(new short[10000],null);},()->{loading.countDown();VoiceSupport.check(release.await(20,TimeUnit.SECONDS),"Test releases model-load latch");return delayed;},new MutableClock());
      VoiceSupport.check(speech.start()&&loading.await(5,TimeUnit.SECONDS),"Model loading started");speech.cancel();VoiceSupport.check(!speech.start(),"Cancelled load cannot be replaced before cleanup");release.countDown();loadingProbe.awaitStopped();VoiceSupport.check(creates.get()==0&&loadingProbe.result.get()==null&&loadingProbe.error.get()==null,"Cancelled model load cannot later start or publish a recording");close(speech,delayed);noTemp(fixture.context);report.put("cancelDuringLoad",true);
      // Cancel an active capture after PCM was received: no audio/text result or temporary original survives.
      FakeModel cancelled=new FakeModel();ScriptedSource running=new ScriptedSource(new short[16000],null);Probe cancelProbe=new Probe();speech=new OfflineSpeech(fixture.context,cancelProbe,()->running,()->cancelled,new MutableClock());VoiceSupport.check(speech.start(),"Cancelable capture starts");cancelProbe.awaitListening();VoiceSupport.check(running.drained.await(5,TimeUnit.SECONDS),"Cancelable capture received PCM");speech.cancel();cancelProbe.awaitStopped();VoiceSupport.check(running.closed&&cancelProbe.result.get()==null&&cancelProbe.error.get()==null&&!speech.isActive()&&store.all("files").length()==fileCount,"Active cancellation releases source and discards unsent WAV/text");close(speech,cancelled);VoiceSupport.check(cancelled.streams.get()==cancelled.released.get(),"Every opened stream was released after cancel");noTemp(fixture.context);report.put("cancelDuringCapture",true);
      FakeModel broken=new FakeModel();Probe failedProbe=new Probe();AtomicInteger failedSources=new AtomicInteger();speech=new OfflineSpeech(fixture.context,failedProbe,()->{failedSources.incrementAndGet();return empty;},()->{throw new IOException("synthetic missing SDK/model");},new MutableClock());VoiceSupport.check(speech.start(),"Missing-model session starts asynchronous preparation");failedProbe.awaitStopped();VoiceSupport.check(failedSources.get()==0&&failedProbe.result.get()==null&&failedProbe.error.get()!=null&&store.all("files").length()==fileCount,"Model initialization failure stays local and never starts the microphone or upload fallback");speech.close();report.put("modelFailure","local friendly error; no microphone or file");
      VoiceSupport.check(store.all("messages").length()==0&&store.all("memories").length()==0,"None of the controller lifecycle paths auto-sends or calls the AI");
    }
    return report;
  }
  public static JSONObject microphone(Instrumentation test)throws Exception{
    VoiceSupport.check(test.getTargetContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED,"Live AudioRecord lifecycle requires the explicitly granted fixture permission");
    try(VoiceSupport.StoreScope fixture=new VoiceSupport.StoreScope(test.getTargetContext())){
      Probe cancel=new Probe();OfflineSpeech speech=new OfflineSpeech(fixture.context,cancel);try{VoiceSupport.check(speech.start(),"Real microphone capture starts");cancel.awaitListening();Thread.sleep(350);speech.cancel();cancel.awaitStopped();VoiceSupport.check(!speech.isActive()&&cancel.result.get()==null&&fixture.db.all("files").length()==0,"Real microphone cancellation releases AudioRecord and retains no unwanted file");}finally{speech.close();}
      noTemp(fixture.context);Probe stop=new Probe();speech=new OfflineSpeech(fixture.context,stop);try{VoiceSupport.check(speech.start(),"Real microphone restarts after cancellation");stop.awaitListening();Thread.sleep(550);speech.stop();stop.awaitStopped();VoiceSupport.check(!speech.isActive(),"Real microphone normal stop releases active session");if(stop.result.get()!=null){JSONObject file=stop.result.get().getJSONObject("file");VoiceSupport.check(file.optBoolean("localVoice")&&file.getString("mime").equals("audio/wav")&&fixture.db.filePath(file.getString("id")).isFile(),"Real microphone stop stores its WAV locally");}else VoiceSupport.check(stop.error.get()!=null,"No captured microphone PCM is explained to the user");VoiceSupport.check(fixture.db.all("messages").length()==0,"Real microphone capture does not send automatically");}finally{speech.close();}
      noTemp(fixture.context);return new JSONObject().put("realAudioRecordStartCancelRestartStop",true).put("recordingResult",stop.result.get()!=null?"local WAV":"friendly no-audio error").put("liveSpokenChineseAccuracy","not assessed; no spoken Chinese was injected into the microphone");
    }
  }
}
