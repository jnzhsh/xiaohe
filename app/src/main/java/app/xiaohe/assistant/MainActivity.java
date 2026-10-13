package app.xiaohe.assistant;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.FrameLayout;
import android.widget.Toast;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public final class MainActivity extends Activity {
  private WebView web;private Store store;private final ExecutorService io=Executors.newSingleThreadExecutor();
  private final ExecutorService feedbackIo=Executors.newSingleThreadExecutor();private Feedback feedback;
  private boolean ready=false,waitingVoice=false,notificationRequested=false;private JSONArray sharedFiles=new JSONArray();private String sharedText="";
  private OfflineSpeech speech;private boolean foreground;private String pickerRequest,exportRequest,importRequest;
  private static final int PICK=10,EXPORT=11,IMPORT=12,MIC=13,NOTIFY=14;
  @Override public void onCreate(Bundle saved){super.onCreate(saved);store=Store.get(this);Scheduler.init(this);if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)WebView.setWebContentsDebuggingEnabled(true);web=new ClipboardWebView(this);web.setBackgroundColor(Color.rgb(255,249,241));
    FrameLayout content=new FrameLayout(this);content.setBackgroundColor(Color.rgb(255,249,241));
    content.addView(web,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
    if(Build.VERSION.SDK_INT>=30){
      getWindow().setDecorFitsSystemWindows(false);
      content.setOnApplyWindowInsetsListener((v,insets)->{
        int bars=WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout();
        int handled=bars|WindowInsets.Type.ime();
        Insets space=insets.getInsets(handled);
        // Padding the container changes WebView's measured viewport, including on older WebViews.
        v.setPadding(space.left,space.top,space.right,space.bottom);
        // Pass zeroed insets onward so newer WebViews do not reserve the same space again.
        return new WindowInsets.Builder(insets).setInsets(handled,Insets.NONE).setInsetsIgnoringVisibility(bars,Insets.NONE).build();
      });
    }
    // API 26-29 uses the window's existing adjustResize and system-bar fitting.
    setContentView(content);content.post(content::requestApplyInsets);
    getWindow().setStatusBarColor(Color.rgb(255,249,241));getWindow().setNavigationBarColor(Color.rgb(255,249,241));View decor=getWindow().getDecorView();decor.setSystemUiVisibility(decor.getSystemUiVisibility()|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|(Build.VERSION.SDK_INT>=26?View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0));
    WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setJavaScriptCanOpenWindowsAutomatically(false);s.setSupportMultipleWindows(false);web.addJavascriptInterface(new Bridge(),"XiaoheAndroid");
    web.setWebViewClient(new WebViewClient(){
      @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest req){return resource(req.getUrl());}
      @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest req){if("xiaohe.local".equals(req.getUrl().getHost()))return false;openUrl(req.getUrl().toString());return true;}
      @Override public void onPageFinished(WebView v,String url){if(url.startsWith("https://xiaohe.local/")){ready=true;deliverShared();}}
    });
    web.loadUrl("https://xiaohe.local/index.html");receive(getIntent());
  }
  @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);getWindow().getDecorView().requestApplyInsets();}
  private WebResourceResponse resource(Uri uri){if(!"xiaohe.local".equals(uri.getHost()))return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
    try{String path=uri.getPath();if(path==null||path.equals("/"))path="/index.html";InputStream stream;String mime;
      if(path.startsWith("/media/")){String id=path.substring(7);JSONObject f=store.one("files",id);if(f==null)throw new IOException();mime=f.optString("mime","application/octet-stream");stream=new FileInputStream(store.filePath(id));}
      else {String asset=path.substring(1);if(asset.contains("..")||asset.contains("\\"))throw new IOException();stream=getAssets().open(asset);mime=asset.endsWith(".js")?"application/javascript":asset.endsWith(".css")?"text/css":asset.endsWith(".wasm")?"application/wasm":asset.endsWith(".gz")?"application/gzip":asset.endsWith(".svg")?"image/svg+xml":asset.endsWith(".html")?"text/html":"application/octet-stream";}
      Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","no-store");headers.put("Access-Control-Allow-Origin","https://xiaohe.local");headers.put("X-Content-Type-Options","nosniff");return new WebResourceResponse(mime,mime.startsWith("text/")||mime.contains("javascript")?"UTF-8":null,200,"OK",headers,stream);
    }catch(Exception e){return new WebResourceResponse("text/plain","UTF-8",404,"Not found",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}}
  private void result(String request,Object value,String error){runOnUiThread(()->{if(isFinishing()||isDestroyed())return;String json=value==null?"null":value.toString();String script="window.NativeBridge&&window.NativeBridge.resolve("+JSONObject.quote(request)+",JSON.parse("+JSONObject.quote(json)+"),"+(error==null?"null":JSONObject.quote(error))+")";web.evaluateJavascript(script.replace("\u2028","\\u2028").replace("\u2029","\\u2029"),null);});}
  private void event(String name,Object value){runOnUiThread(()->{if(!ready||isFinishing()||isDestroyed())return;String json=value==null?"null":value.toString();web.evaluateJavascript("window.NativeBridge&&window.NativeBridge.event("+JSONObject.quote(name)+",JSON.parse("+JSONObject.quote(json)+"))",null);});}
  public final class Bridge {
    @JavascriptInterface public void invoke(String method,String raw,String request){
      if(Feedback.accepts(method)){
        feedbackIo.execute(()->{try{if(feedback==null)feedback=new Feedback(MainActivity.this);result(request,feedback.handle(method,new JSONObject(raw)),null);}
          catch(JSONException e){result(request,null,"反馈内容没读完整，输入还在，可以再试。");}catch(Exception e){result(request,null,friendly(e));}});return;
      }
      if(Arrays.asList("pickFiles","export","import","voice","openFile","openUrl","reminder","exactAlarmSettings","readClipboard","preparePaste").contains(method)){runOnUiThread(()->{try{uiCall(method,new JSONObject(raw),request);}catch(Exception e){result(request,null,friendly(e));}});return;}
      io.execute(()->{try{JSONObject args=new JSONObject(raw);Object response=call(method,args);result(request,response,null);}catch(Exception e){result(request,null,friendly(e));}});
    }
  }
  private Object call(String method,JSONObject args)throws Exception {
    switch(method){
      case "bootstrap":store.archive(false);JSONObject boot=store.bootstrap();boot.put("notifications",canNotify());boot.put("exactAlarms",Build.VERSION.SDK_INT<31||getSystemService(AlarmManager.class).canScheduleExactAlarms());if(getIntent().hasExtra("memoryId")){boot.put("openMemoryId",getIntent().getStringExtra("memoryId"));getIntent().removeExtra("memoryId");}return boot;
      case "saveSettings":new Settings(this).save(args);return new Settings(this).publicValues();
      case "testConnection":return new AiEngine(this).test();
      case "stageMessage":{String id=args.optString("id",UUID.randomUUID().toString());if(!id.matches("[a-zA-Z0-9_-]{8,80}"))throw new Exception("无效消息编号");JSONArray files=args.optJSONArray("files");if(files==null)files=new JSONArray();for(int i=0;i<files.length();i++)if(store.one("files",files.getString(i))==null)throw new Exception("附件不存在");return stageMessage(args,files,id);}
      case "prepareMedia":return new MediaProcessor(this).prepare(args.getJSONArray("files"));
      case "saveOcr":{JSONObject f=store.one("files",args.getString("id"));if(f==null)throw new Exception("附件不存在");String text=args.optString("text");f.put("ocrText",text);f.put("processingNote",f.optString("processingNote").replaceAll("；本机OCR.*$","")+(text.isEmpty()?"；本机OCR未识别到可读文字":"；本机OCR完成，识别文字仍可能有误"));store.put("files",f);return f;}
      case "processMessage":return process(args);
      case "localSearch":return store.search(args.optString("query"),100);
      case "getMessage":return store.one("messages",args.getString("id"));
      case "getMemory":return store.one("memories",args.getString("id"));
      case "getConflicts":{JSONObject memory=store.one("memories",args.getString("memoryId"));JSONArray groups=memory==null?new JSONArray():store.rows("SELECT data FROM conflicts WHERE fact_key=? AND state='open' ORDER BY created DESC",memory.optString("factKey")),matching=new JSONArray();for(int i=0;i<groups.length();i++){JSONObject group=groups.getJSONObject(i);JSONArray ids=group.optJSONArray("memberIds");if(ids!=null)for(int j=0;j<ids.length();j++)if(ids.optString(j).equals(args.getString("memoryId"))){matching.put(group);break;}}return matching;}
      case "entityPage":{String q=MemorySchema.normal(args.optString("query"));int offset=Math.max(0,args.optInt("offset")),limit=Math.min(80,Math.max(1,args.optInt("limit",40)));String where="1=1";List<String> params=new ArrayList<>();if(!q.isEmpty()){String pattern="%"+q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";where="(name_norm LIKE ? ESCAPE '\\' OR qualifier LIKE ? ESCAPE '\\' OR id IN (SELECT entity_id FROM entity_aliases WHERE alias LIKE ? ESCAPE '\\'))";params.add(pattern);params.add(pattern);params.add(pattern);}int total;try(Cursor cursor=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entities WHERE "+where,params.toArray(new String[0]))){cursor.moveToFirst();total=cursor.getInt(0);}params.add(""+limit);params.add(""+offset);JSONArray rows=store.rows("SELECT data FROM entities WHERE "+where+" ORDER BY created DESC LIMIT ? OFFSET ?",params.toArray(new String[0]));return new JSONObject().put("rows",rows).put("total",total).put("hasMore",offset+rows.length()<total);}
      case "memoryPage":return new MemoryLedger(store).page(args);
      case "getHistory":return new MemoryLedger(store).history(args.getString("id"),args.optInt("offset"));
      case "getEntityMemories":return new MemoryLedger(store).byEntity(args.getString("id"),100);
      case "resolveConflict":{Long at=args.has("transitionAt")?args.getLong("transitionAt"):null;JSONObject r=new MemoryLedger(store).resolveConflict(args.getString("id"),args.getString("chosenId"),args.optString("mode","correction"),at);Scheduler.reschedule(this);return r;}
      case "bindEntity":return new MemoryLedger(store).bindEntity(args.getString("id"),args.optString("entityId"),args.optString("name"),args.optString("qualifier"));
      case "deferCare":{JSONObject r=new MemoryLedger(store).deferCare(args.getString("id"));Scheduler.set(this,r);return r;}
      case "confirmTime":return new MemoryLedger(store).confirmTime(args.getString("id"),args.getString("iso"));
      case "restoreVersion":{JSONObject r=new MemoryLedger(store).restoreVersion(args.getString("id"));Scheduler.set(this,r);return r;}
      case "updateMemory":{JSONObject r=new MemoryLedger(store).edit(args,"");Scheduler.set(this,r);return r;}
      case "runArchive":return MemoryOrganizer.run(store,this);
      default:throw new Exception("不支持的操作");
    }
  }
  private JSONObject process(JSONObject args)throws Exception {
    JSONObject m=store.one("messages",args.getString("id"));if(m==null||!m.optString("role").equals("user"))throw new Exception("消息不存在");
    if(m.optBoolean("processed"))return store.bootstrap();
    JSONArray files=m.optJSONArray("files");if(files!=null)for(int i=0;i<files.length();i++){JSONObject f=store.one("files",files.getString(i));if(f!=null&&f.optBoolean("recorded")&&!f.optBoolean("localVoice")&&!f.optString("text").isEmpty()){m.put("voiceText",f.getString("text")).put("recordedVoiceVerified",true);if(m.optString("content").isEmpty())m.put("content",f.getString("text"));}}
    Matcher matcher=Pattern.compile("https?://[^\\s<>\"，。；]+",Pattern.CASE_INSENSITIVE).matcher(m.optString("content"));if(matcher.find()&&!m.has("linkText")){event("progress",new JSONObject().put("text","我在看看链接里能读到的内容…"));try{m.put("linkText",MediaProcessor.fetchLink(matcher.group()));m.put("linkNote","已读取无需登录的网页文字，脚本生成的内容可能未包含");}catch(Exception e){m.put("linkNote","原链接已保留，网页未能读取："+friendly(e));}store.put("messages",m);}
    event("progress",new JSONObject().put("text","我在把这些事整理好…"));
    try{JSONObject ai=new AiEngine(this).process(m,args.optJSONArray("frames"));String replyText=ai.getString("reply");JSONArray eligible=ai.optJSONArray("reminderEligibleIds"),schedules=new JSONArray();if(eligible!=null)for(int i=0;i<eligible.length();i++){JSONObject r=store.one("memories",eligible.getString(i));if(r==null)continue;JSONObject schedule=Scheduler.schedule(this,r);r.put("reminderSchedule",schedule).put("changeReason","手机记录提醒的实际排程结果");store.put("memories",r);schedules.put(schedule);if(schedule.optString("state").equals("scheduled"))requestNotificationPermission();}
      if(schedules.length()>0){boolean scheduled=false,permission=false,approximate=false;long at=0;for(int i=0;i<schedules.length();i++){JSONObject schedule=schedules.getJSONObject(i);if(schedule.optString("state").equals("scheduled")){scheduled=true;at=schedule.optLong("at");permission|=!schedule.optBoolean("notificationsAllowed");approximate|=!schedule.optBoolean("exact");}}if(scheduled){if(permission)replyText+="\n时间记好了，允许通知后我才能提醒你。";else{java.time.ZoneId zone=java.time.ZoneId.of(TimeAnchor.zone(m));String when=java.time.Instant.ofEpochMilli(at).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm",Locale.SIMPLIFIED_CHINESE));replyText+=schedules.length()==1?"\n我会在 "+when+" 提醒你。":"\n这几条提醒的时间都安排好了。";if(approximate)replyText+=" 系统目前按大致时间提醒。";}}else replyText+="\n提醒要求我留着了，这次系统还没接受安排。";}
      JSONObject reply=store.reply(replyText,m.getString("id"),null);reply.put("evidenceIds",ai.optJSONArray("evidenceIds"));if(ai.optJSONObject("care")!=null)reply.put("care",ai.getJSONObject("care"));if(ai.optJSONObject("queryTime")!=null)reply.put("queryTime",ai.getJSONObject("queryTime"));store.put("messages",reply);m.put("processed",true);m.remove("error");store.put("messages",m);Scheduler.reschedule(this);
    }catch(Exception e){String reason=friendly(e);boolean query=m.optString("content").matches("(?s).*(？|\\?|在哪|哪里|找一下|查一下|还记得|有没有|有什么|有哪些|什么安排|哪些安排|什么日程|哪些日程).*" );
      if(query){JSONArray matches=new MemoryLedger(store).search(m.optString("content"),5,m);StringBuilder text=new StringBuilder(matches.length()>0?"AI 暂时没有连上，我先从本机找到这些记录：\n":"这条暂时没在本机找到。你可以换一个名字或关键词再试。\n");for(int i=0;i<matches.length();i++)text.append("\n· ").append(matches.getJSONObject(i).optString("title")).append("：").append(Store.shorten(matches.getJSONObject(i).optString("content"),260)).append(matches.getJSONObject(i).optString("truthState").equals("disputed")?"（说法有分歧，尚待确认）":!MemoryLedger.settled(matches.getJSONObject(i))?"（对象或时间尚待确认）":"");store.reply(text.toString(),m.getString("id"),reason);}
      else {store.pendingRecord(m,reason);store.reply("原始内容已经替你收在手机里了。AI 这次没能完成整理，连接好以后可以再试，不用重新输入。",m.getString("id"),reason);}m.put("error",reason);store.put("messages",m);
    }return store.bootstrap();
  }
  private void uiCall(String method,JSONObject a,String request)throws Exception {
    switch(method){
      case "preparePaste":if(!web.hasWindowFocus())throw new Exception("先点一下小禾的输入框，再粘贴。");((ClipboardWebView)web).finishPasteComposition();result(request,new JSONObject().put("ok",true),null);break;
      case "readClipboard":{
        if(!web.hasWindowFocus())throw new Exception("先回到小禾，再粘贴。");
        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        ClipData clip=clipboard==null?null:clipboard.getPrimaryClip();
        CharSequence text=clip!=null&&clip.getItemCount()>0?clip.getItemAt(0).getText():null;
        if(text==null||text.length()==0)throw new Exception("剪贴板里暂时没有文字，复制后再点粘贴。");
        if(text.length()>4*1024*1024)throw new Exception("这段文字太长，可以作为文件交给我。");
        result(request,new JSONObject().put("text",text.toString()),null);break;
      }
      case "pickFiles":pickerRequest=request;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true),PICK);break;
      case "export":exportRequest=request;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,"小禾备份-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmm",Locale.ROOT).format(new Date())+".xiaohe.zip"),EXPORT);break;
      case "import":importRequest=request;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),IMPORT);break;
      case "voice":{
        String action=a.optString("action","toggle");
        if(action.equals("cancel")){waitingVoice=false;if(speech!=null&&speech.isActive())speech.cancel();else event("voiceState",new JSONObject().put("state","stopped"));}
        else if(action.equals("stop")||(action.equals("toggle")&&speech!=null&&speech.isActive())){if(speech!=null&&speech.isActive())speech.stop();else event("voiceState",jsonState("stopped"));}
        else if(!waitingVoice){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){waitingVoice=true;event("voiceState",new JSONObject().put("state","permission"));requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC);}else startVoice();}
        result(request,new JSONObject().put("ok",true),null);break;
      }
      case "openFile":{JSONObject f=store.one("files",a.getString("id"));if(f==null)throw new Exception("附件不存在");Intent open=new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://app.xiaohe.assistant.files/"+f.getString("id")),f.optString("mime","application/octet-stream")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(open,"打开原件"));result(request,new JSONObject().put("ok",true),null);break;}
      case "openUrl":openUrl(a.getString("url"));result(request,new JSONObject().put("ok",true),null);break;
      case "reminder":pickReminder(a.getString("id"),request);break;
      case "exactAlarmSettings":if(Build.VERSION.SDK_INT>=31)startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));result(request,new JSONObject().put("ok",true),null);break;
    }
  }
  @Override protected void onActivityResult(int code,int resultCode,Intent data){super.onActivityResult(code,resultCode,data);String request=code==PICK?pickerRequest:code==EXPORT?exportRequest:importRequest;if(resultCode!=RESULT_OK||data==null){if(request!=null)result(request,new JSONObject(),null);return;}
    io.execute(()->{try{if(code==PICK){JSONArray files=new JSONArray();List<Uri> uris=new ArrayList<>();if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());else if(data.getData()!=null)uris.add(data.getData());for(Uri u:uris)files.put(new MediaProcessor(this).copy(u));result(request,new JSONObject().put("files",files),null);}
      else if(code==EXPORT){try(OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new Exception("无法写入备份");new Backup(this).exportTo(out);}result(request,new JSONObject().put("ok",true),null);}
      else if(code==IMPORT){JSONObject r;try(InputStream in=getContentResolver().openInputStream(data.getData())){if(in==null)throw new Exception("无法读取备份");r=new Backup(this).importFrom(in);}Scheduler.reschedule(this);result(request,r,null);}}catch(Exception e){result(request,null,friendly(e));}});
  }
  private void receive(Intent intent){if(intent==null)return;String action=intent.getAction();if(Intent.ACTION_SEND.equals(action)||Intent.ACTION_SEND_MULTIPLE.equals(action)){CharSequence text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT);sharedText=text==null?"":text.toString();List<Uri> uris=new ArrayList<>();if(Intent.ACTION_SEND_MULTIPLE.equals(action)){ArrayList<Uri> list=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);if(list!=null)uris.addAll(list);}else{Uri uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);if(uri!=null)uris.add(uri);}io.execute(()->{try{for(Uri uri:uris)sharedFiles.put(new MediaProcessor(this).copy(uri));runOnUiThread(this::deliverShared);}catch(Exception e){event("error",jsonText(friendly(e)));}});}else if(intent.hasExtra("memoryId"))sharedText="";}
  @Override public void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);receive(i);}
  private void deliverShared(){if(!ready||(sharedText.isEmpty()&&sharedFiles.length()==0))return;try{event("shared",new JSONObject().put("text",sharedText).put("files",sharedFiles));sharedText="";sharedFiles=new JSONArray();}catch(Exception ignored){}}
  private JSONObject stageMessage(JSONObject args,JSONArray files,String id)throws Exception {
    if(!args.has("capturedAt")&&!args.has("capturedZone")&&!args.has("anchorFileId"))return store.message("user",args.optString("text"),files,id);
    long at=args.getLong("capturedAt");String zone=args.getString("capturedZone"),fileId=args.getString("anchorFileId");
    JSONObject anchorFile=store.one("files",fileId);
    if(at<=0||anchorFile==null||!anchorFile.optBoolean("localVoice")||anchorFile.optLong("capturedAt")!=at||!anchorFile.optString("capturedZone").equals(zone))throw new Exception("这段语音的原始时间暂时没找到，可以先清空草稿再输入。");
    try{java.time.ZoneId.of(zone);java.time.Instant.ofEpochMilli(at);}catch(Exception e){throw new Exception("这段语音的时间信息不完整。");}
    return store.message("user",args.optString("text"),files,id,at,zone);
  }
  private void startVoice(){
    if(isFinishing()||isDestroyed())return;if(!foreground){event("voiceState",jsonState("stopped"));return;}
    android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
    if(keyboard!=null)keyboard.hideSoftInputFromWindow(web.getWindowToken(),0);
    if(speech==null)speech=new OfflineSpeech(this,new OfflineSpeech.Listener(){
      public void onState(JSONObject state){event("voiceState",state);}
      public void onPartial(JSONObject text){event("voicePartial",text);}
      public void onResult(JSONObject value){event("voiceResult",value);}
      public void onError(String message){event("error",jsonText(message));}
    });
    speech.start();
  }
  @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grant){
    super.onRequestPermissionsResult(code,permissions,grant);
    if(code==MIC&&waitingVoice){waitingVoice=false;
      if(grant.length>0&&grant[0]==PackageManager.PERMISSION_GRANTED)web.post(this::startVoice);
      else {event("voiceState",jsonState("stopped"));event("error",jsonText("录音权限没有开启，仍然可以输入文字或添加已有素材。"));}
    }
    if(code==NOTIFY&&grant.length>0&&grant[0]==PackageManager.PERMISSION_GRANTED)io.execute(()->{try{Scheduler.reschedule(this);}catch(Exception ignored){}});
  }
  private static JSONObject jsonState(String state){try{return new JSONObject().put("state",state);}catch(Exception e){return new JSONObject();}}
  @Override protected void onResume(){super.onResume();foreground=true;}
  @Override protected void onPause(){foreground=false;if(speech!=null&&speech.isActive())speech.cancel();super.onPause();}
  private void requestNotificationPermission(){if(Build.VERSION.SDK_INT>=33&&!canNotify()&&!notificationRequested){notificationRequested=true;runOnUiThread(()->requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFY));}}
  private boolean canNotify(){return Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;}
  private void pickReminder(String id,String request)throws Exception {
    Calendar now=Calendar.getInstance();
    DatePickerDialog dateDialog=new DatePickerDialog(this,(date,y,m,d)->{
      TimePickerDialog timeDialog=new TimePickerDialog(this,(time,h,min)->io.execute(()->{try{Calendar chosen=Calendar.getInstance();chosen.set(y,m,d,h,min,0);chosen.set(Calendar.MILLISECOND,0);if(chosen.getTimeInMillis()<=System.currentTimeMillis())throw new Exception("提醒时间需要在现在之后");JSONObject r=store.one("memories",id);if(r==null)throw new Exception("记录不存在");r.put("remindAt",chosen.getTimeInMillis());r.put("reminderFired",false);r.put("reminderConfirmed",true);r.remove("careDeferredSourceId");r.put("reminderRequested",true);r.put("changeReason","用户明确选择提醒日期与时间");r.put("reminderSchedule",Scheduler.schedule(this,r));store.put("memories",r);requestNotificationPermission();result(request,r,null);}catch(Exception e){result(request,null,friendly(e));}}),now.get(Calendar.HOUR_OF_DAY),now.get(Calendar.MINUTE),true);
      timeDialog.setOnCancelListener(dialog->result(request,new JSONObject(),null));timeDialog.show();
    },now.get(Calendar.YEAR),now.get(Calendar.MONTH),now.get(Calendar.DAY_OF_MONTH));
    dateDialog.setOnCancelListener(dialog->result(request,new JSONObject(),null));dateDialog.show();
  }
  private void openUrl(String url){try{Uri u=Uri.parse(url);if(!Arrays.asList("http","https").contains(u.getScheme()))throw new Exception("只打开网页链接");startActivity(new Intent(Intent.ACTION_VIEW,u));}catch(Exception e){Toast.makeText(this,"暂时无法打开这个链接",Toast.LENGTH_SHORT).show();}}
  private static JSONObject jsonText(String text){try{return new JSONObject().put("text",text);}catch(Exception e){return new JSONObject();}}
  private String friendly(Exception e){String message=e.getMessage()==null?"这次没有完成，原始内容仍保存在手机里":e.getMessage();try{Settings settings=new Settings(this);for(String name:new String[]{"apiKey","asrKey","githubToken"}){String secret=settings.secret(name);if(!secret.isEmpty())message=message.replace(secret,"[密钥已隐藏]");}}catch(Exception ignored){}message=message.replaceAll("(?i)(?:ghp_|github_pat_|gh[ousr]_)[A-Za-z0-9_]+","[密钥已隐藏]").replaceAll("(?i)(Authorization\\s*:\\s*Bearer\\s+|\\bBearer\\s+)[^\\s\\r\\n\"'<>]+","$1[密钥已隐藏]");return Store.shorten(message,260);}
  @Override public void onBackPressed(){if(!ready){super.onBackPressed();return;}web.evaluateJavascript("window.App?window.App.back():false",value->{if(!"true".equals(value))MainActivity.super.onBackPressed();});}
  @Override public void onDestroy(){waitingVoice=false;if(speech!=null)speech.close();io.shutdown();feedbackIo.shutdown();if(web!=null){web.removeJavascriptInterface("XiaoheAndroid");web.destroy();}super.onDestroy();}
}
