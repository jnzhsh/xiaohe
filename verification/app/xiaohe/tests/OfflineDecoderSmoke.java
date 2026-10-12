package app.xiaohe.tests;

import android.app.Instrumentation;
import android.os.SystemClock;
import app.xiaohe.assistant.OfflineSpeech;
import org.json.*;

/** Actual packaged production assets + native sherpa-onnx, not a mock recognizer. */
public final class OfflineDecoderSmoke {
  private static final String REFERENCE="对我做了介绍那么我想说的是大家如果对我的研究感兴趣";
  // Official Apache-2.0 Chinese 14M model archive test_wavs examples.
  // https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2
  private static String decode(OfflineSpeech.RecognitionModel model,short[] pcm)throws Exception{
    OfflineSpeech.Decoder decoder=model.createDecoder();try{for(int offset=0;offset<pcm.length;offset+=1600){int count=Math.min(1600,pcm.length-offset);short[] chunk=new short[count];System.arraycopy(pcm,offset,chunk,0,count);decoder.accept(chunk,count);}decoder.finish();return decoder.text();}finally{decoder.close();}
  }
  public static JSONObject run(Instrumentation test)throws Exception{
    String zeroSha=VoiceSupport.hash(test.getContext().getAssets().open("0.wav"));
    String eightSha=VoiceSupport.hash(test.getContext().getAssets().open("8k.wav"));
    VoiceSupport.check(zeroSha.equals("668bf8df51a10027b84d5d8816a1ce11ae93545538dc05cfe2aa6811d399c250"),"Official 0.wav fixture checksum");
    VoiceSupport.check(eightSha.equals("0ed3bddacf0a23477d2b3c07ff9c24ad9c1fc3b31cdd47cb0773441e8e045cde"),"Official 8k.wav fixture checksum");
    VoiceSupport.Wave zero=VoiceSupport.Wave.read(test.getContext().getAssets().open("0.wav"));
    VoiceSupport.Wave eight=VoiceSupport.Wave.read(test.getContext().getAssets().open("8k.wav"));
    JSONArray examples=new JSONArray();long cold=SystemClock.elapsedRealtime();OfflineSpeech.RecognitionModel model=new OfflineSpeech.SherpaModel(test.getTargetContext().getAssets());long loadMs=SystemClock.elapsedRealtime()-cold;
    try{
      for(int round=0;round<2;round++){long start=SystemClock.elapsedRealtime();String text=decode(model,zero.at16k());long elapsed=SystemClock.elapsedRealtime()-start;VoiceSupport.check(VoiceSupport.normalize(text).equals(REFERENCE),"Production Android recognizer must exactly match official Chinese 0.wav reference on repeat "+round);examples.put(new JSONObject().put("fixture","0.wav").put("round",round).put("sourceRate",zero.sampleRate).put("audioSeconds",zero.samples.length/(double)zero.sampleRate).put("decodeMs",elapsed).put("text",text).put("exactReferenceMatch",true));}
      String silence=decode(model,new short[16000]);VoiceSupport.check(silence.trim().isEmpty(),"One second silence cannot create a spoken text");
      VoiceSupport.Wave tomorrow=VoiceSupport.Wave.read(test.getContext().getAssets().open("tomorrow-zh.wav"));String future=decode(model,tomorrow.at16k());
      VoiceSupport.check(future.contains("明天")&&future.contains("下午三点"),"Synthetic spoken relative date and Chinese clock must survive the production decoder: "+future);
      examples.put(new JSONObject().put("fixture","tomorrow-zh.wav").put("sourceRate",tomorrow.sampleRate).put("text",future).put("relativeDateAndClockPreserved",true).put("exactReferenceMatch","not required"));
      String resampled=decode(model,eight.at16k());VoiceSupport.check(resampled.matches("(?s).*[\\u4e00-\\u9fff].*"),"Locally resampled public 8 kHz audio decodes Chinese without crashing");examples.put(new JSONObject().put("fixture","8k.wav").put("sourceRate",eight.sampleRate).put("resampledRate",16000).put("text",resampled).put("exactReferenceMatch","not assessed"));
    }finally{model.close();}
    // Recreating the engine after releasing its JNI streams and model must remain usable.
    model=new OfflineSpeech.SherpaModel(test.getTargetContext().getAssets());try{VoiceSupport.check(VoiceSupport.normalize(decode(model,zero.at16k())).equals(REFERENCE),"Recognition remains usable after complete model release and reload");}finally{model.close();}
    return new JSONObject().put("engine","production sherpa-onnx CPU via packaged Android JNI").put("model","zh-14m").put("coldLoadMs",loadMs).put("fixtures",examples).put("silenceTextEmpty",true).put("modelReloadPassed",true).put("liveMicrophoneChinese","not measured by prerecorded decoder test");
  }
}
