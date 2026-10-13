package app.xiaohe.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** Reviewed feedback only. Life records and their backup format are not changed. */
public final class Feedback {
  public static final String REPO="jnzhsh/xiaohe";
  public static final String REPOSITORY_PATH="/repos/"+REPO, ISSUES_PATH=REPOSITORY_PATH+"/issues";
  private static final Object OUTBOX_LOCK=new Object();
  private static final int TITLE_LIMIT=180, DESCRIPTION_LIMIT=8000, CONTEXT_LIMIT=4000;
  private static final String HIDDEN="[密钥已隐藏]";
  private static final Pattern TOKEN_PATTERN=Pattern.compile("(?i)(?:ghp_|github_pat_|gh[ousr]_)[A-Za-z0-9_]+");
  private static final Pattern BEARER_PATTERN=Pattern.compile("(?i)(Authorization\\s*:\\s*Bearer\\s+|\\bBearer\\s+)[^\\s\\r\\n\"'<>]+");
  public interface Transport {Response execute(String method,String path,JSONObject body,String token)throws IOException;}
  public static final class Response {
    public final int status;public final String body;public final Map<String,String> headers;
    public Response(int status,String body){this(status,body,Collections.emptyMap());}
    public Response(int status,String body,Map<String,String> headers){this.status=status;this.body=body==null?"":body;this.headers=headers==null?Collections.emptyMap():new HashMap<>(headers);}
    public String header(String name){for(Map.Entry<String,String> item:headers.entrySet())if(item.getKey().equalsIgnoreCase(name))return item.getValue();return "";}
  }
  public static final class HttpTransport implements Transport {
    public Response execute(String method,String path,JSONObject body,String token)throws IOException {
      boolean page=path.matches(Pattern.quote(ISSUES_PATH)+"\\?state=all&sort=created&direction=desc&per_page=100&page=[1-5]");
      if((!method.equals("GET")&&!method.equals("POST"))||(!path.equals(REPOSITORY_PATH)&&!path.equals(ISSUES_PATH)&&!page)||method.equals("POST")&&!path.equals(ISSUES_PATH))throw new IOException("反馈地址不正确");
      HttpURLConnection connection=(HttpURLConnection)new URL("https://api.github.com"+path).openConnection();
      connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(12000);connection.setReadTimeout(25000);
      connection.setRequestMethod(method);connection.setRequestProperty("Accept","application/vnd.github+json");
      connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28");connection.setRequestProperty("User-Agent","Xiaohe-Android-Feedback");
      connection.setRequestProperty("Authorization","Bearer "+token);
      try{
        if(method.equals("POST")){if(body==null)throw new IOException("反馈内容为空");byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setFixedLengthStreamingMode(bytes.length);try(OutputStream output=connection.getOutputStream()){output.write(bytes);}}
        int status=connection.getResponseCode();InputStream source=status>=400?connection.getErrorStream():connection.getInputStream();String value="";
        if(source!=null)try(InputStream input=source;ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(output.size()+n>8*1024*1024)throw new IOException("反馈服务的回应太长");output.write(buffer,0,n);}value=output.toString("UTF-8");}
        Map<String,String> headers=new HashMap<>();Map<String,List<String>> all=connection.getHeaderFields();if(all!=null)for(Map.Entry<String,List<String>> item:all.entrySet())if(item.getKey()!=null&&item.getValue()!=null&&!item.getValue().isEmpty())headers.put(item.getKey(),item.getValue().get(0));
        return new Response(status,value,headers);
      }finally{connection.disconnect();}
    }
  }

  private final Context context;private final SharedPreferences prefs;private final Settings settings;private final Transport transport;
  public Feedback(Context context){this(context,new HttpTransport());}
  public Feedback(Context context,Transport transport){this.context=context.getApplicationContext();this.prefs=this.context.getSharedPreferences("feedback",0);this.settings=new Settings(this.context);this.transport=transport;}
  public static boolean accepts(String method){return Arrays.asList("feedbackOpen","feedbackSave","feedbackPrepare","feedbackSubmit","feedbackCheck","feedbackConfigure","feedbackConnection").contains(method);}
  public JSONObject handle(String method,JSONObject args)throws Exception {
    synchronized(OUTBOX_LOCK){
      if(args==null)args=new JSONObject();recoverInterrupted();sanitizeStored();
      switch(method){
        case "feedbackOpen":return open(args);
        case "feedbackSave":return save(args,false);
        case "feedbackPrepare":return save(args,true);
        case "feedbackSubmit":return submit(args);
        case "feedbackCheck":return check(args);
        case "feedbackConfigure":settings.saveGithubToken(string(args,"token",""),option(args,"clearToken",false));sanitizeStored();return publicState();
        case "feedbackConnection":return connection();
        default:throw new Exception("这个反馈操作暂时不可用。");
      }
    }
  }
  private JSONObject read(String key)throws Exception {String value=prefs.getString(key,"");return value.isEmpty()?null:new JSONObject(value);}
  private JSONObject draft()throws Exception {return read("draft");}
  private JSONObject outbox()throws Exception {JSONObject value=read("outbox");return value==null?new JSONObject().put("state","editing"):value;}
  private void persist(JSONObject draft,JSONObject outbox)throws Exception {
    SharedPreferences.Editor editor=prefs.edit();if(draft==null)editor.remove("draft");else editor.putString("draft",draft.toString());
    if(outbox==null)editor.remove("outbox");else editor.putString("outbox",outbox.toString());
    if(!editor.commit())throw new Exception("反馈草稿没有存好，请检查手机剩余空间。");
  }
  private JSONObject configuration()throws JSONException {return new JSONObject().put("hasToken",!settings.get("githubToken","").isEmpty()).put("repo",REPO);}
  private JSONObject publicState()throws Exception {
    JSONObject result=new JSONObject().put("draft",draft()==null?JSONObject.NULL:draft()).put("config",configuration());JSONObject box=outbox();
    result.put("state",box.optString("state","editing"));if(box.optJSONObject("preview")!=null)result.put("preview",box.getJSONObject("preview"));if(box.optJSONObject("receipt")!=null)result.put("receipt",box.getJSONObject("receipt"));if(!box.optString("message").isEmpty())result.put("message",box.getString("message"));return result;
  }
  private void recoverInterrupted()throws Exception {JSONObject box=outbox();if(box.optString("state").equals("inFlight")){box.put("state","uncertain").put("message",uncertainMessage());persist(draft(),box);}}
  private JSONObject open(JSONObject args)throws Exception {
    JSONObject current=draft(),box=outbox();boolean explicitlyNew=option(args,"newDraft",false),fresh=current==null||explicitlyNew,previousUncertain=box.optString("state").equals("uncertain");
    if(fresh&&previousUncertain)return publicState().put("message","上次反馈的发布结果还没确认，先核对一下，避免重复发布。");
    if(fresh){String messageId=assistantId(string(args,"messageId",""));current=new JSONObject().put("id",UUID.randomUUID().toString()).put("title",messageId.isEmpty()?"小禾使用反馈":"关于这次回答的反馈").put("description","").put("messageId",messageId).put("includeConversation",false).put("includeDiagnostics",true);box=new JSONObject().put("draftId",current.getString("id")).put("state","editing");persist(current,box);}
    else if(args.has("messageId")&&box.optString("state").equals("editing")&&current.optString("description").isEmpty()){
      String messageId=assistantId(string(args,"messageId",""));if(!messageId.equals(current.optString("messageId"))){current.put("messageId",messageId).put("includeConversation",false);box.remove("preview");persist(current,box);}
    }
    return publicState();
  }
  private JSONObject save(JSONObject args,boolean prepare)throws Exception {
    JSONObject current=draft();if(current==null){open(new JSONObject());current=draft();}
    JSONObject box=outbox();String state=box.optString("state","editing");
    if(state.equals("submitted"))return publicState().put("message","这条反馈已经发布。新的问题可以另写一条。");
    if(state.equals("uncertain"))throw new Exception("先核对上次的发布结果，这份待确认的预览会替你保留。");
    JSONObject requested=args.optJSONObject("draft");if(requested==null)throw new Exception("反馈内容暂时没找到。");
    String id=string(requested,"id",current.getString("id"));if(!id.equals(current.getString("id")))throw new Exception("草稿已经变化了，请重新打开反馈。");
    String rawTitle=string(requested,"title",current.optString("title")),rawDescription=string(requested,"description",current.optString("description"));
    String title=cut(redact(rawTitle).replaceAll("[\\r\\n]+"," ").trim(),TITLE_LIMIT),description=cut(redact(rawDescription).trim(),DESCRIPTION_LIMIT);
    String messageId=assistantId(string(requested,"messageId",current.optString("messageId")));
    current=new JSONObject().put("id",id).put("title",title).put("description",description).put("messageId",messageId)
      .put("includeConversation",option(requested,"includeConversation",current.optBoolean("includeConversation")))
      .put("includeDiagnostics",option(requested,"includeDiagnostics",current.optBoolean("includeDiagnostics",true)));
    boolean removed=!rawTitle.equals(redact(rawTitle))||!rawDescription.equals(redact(rawDescription))||title.contains(HIDDEN)||description.contains(HIDDEN);
    box=new JSONObject().put("draftId",id).put("state","editing").put("redacted",removed);
    persist(current,box);
    if(prepare){if(title.isEmpty())throw new Exception("先给这条反馈起一个简短标题。");if(description.isEmpty())throw new Exception("写一点遇到的问题或期待的表现，我就能帮你准备反馈。");JSONObject preview=preview(current,removed);box.put("preview",preview).put("state","ready");persist(current,box);}
    return publicState();
  }
  private JSONObject preview(JSONObject draft,boolean removed)throws Exception {
    StringBuilder body=new StringBuilder("## 遇到的问题或建议\n\n").append(draft.getString("description"));boolean included=false;
    JSONObject message=selectedMessage(draft);if(draft.optBoolean("includeConversation")&&message!=null){included=true;body.append("\n\n## 我选择附上的这一轮对话\n\n");JSONObject source=Store.get(context).one("messages",message.optString("sourceId"));
      if(source!=null&&source.optString("role").equals("user")){String raw=source.optString("content"),safe=redact(raw);removed|=!raw.equals(safe);body.append("用户：\n\n").append(cut(safe,CONTEXT_LIMIT)).append("\n\n");}
      String raw=message.optString("content"),safe=redact(raw);removed|=!raw.equals(safe);body.append("小禾：\n\n").append(cut(safe,CONTEXT_LIMIT));
    }
    if(draft.optBoolean("includeDiagnostics",true)){
      String version="未知";try{version=context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}catch(Exception ignored){}
      String rawModel=settings.get("model","").isEmpty()?"未填写":settings.get("model",""),model=redact(rawModel);removed|=!rawModel.equals(model);
      body.append("\n\n## 应用信息\n\n- 小禾版本：").append(version).append("\n- Android API：").append(Build.VERSION.SDK_INT).append("\n- 对话模型：").append(cut(model,120));
      if(message!=null){JSONArray ids=message.optJSONArray("evidenceIds");if(ids!=null){int missing=0;for(int i=0;i<ids.length();i++)if(Store.get(context).one("memories",ids.optString(i))==null)missing++;body.append("\n- 回答中的依据数量：").append(ids.length()).append("\n- 本机未找到的依据数量：").append(missing);}}
    }
    body.append("\n\n").append(marker(draft.getString("id")));String original=body.toString(),safe=redact(original),title=redact(draft.getString("title"));removed|=!original.equals(safe);
    return new JSONObject().put("draftId",draft.getString("id")).put("hash",hash(title,safe)).put("title",title).put("body",safe).put("redacted",removed).put("contextIncluded",included);
  }
  private JSONObject submit(JSONObject args)throws Exception {
    JSONObject current=draft(),box=outbox();if(current==null)throw new Exception("先写下反馈，再看看发布预览。");
    if(!current.getString("id").equals(string(args,"draftId","")))throw new Exception("草稿已经变化了，请重新查看预览。");
    if(box.optString("state").equals("submitted")&&box.optJSONObject("receipt")!=null)return publicState();
    JSONObject preview=box.optJSONObject("preview");String requestedHash=string(args,"previewHash","");
    if(preview==null||requestedHash.isEmpty()||!requestedHash.equals(preview.optString("hash"))||!hash(preview.optString("title"),preview.optString("body")).equals(requestedHash))throw new Exception("预览已经变化了，请重新看一下，再确认发布。");
    if(box.optString("state").equals("uncertain"))return publicState().put("message",uncertainMessage());
    if(!box.optString("state").equals("ready"))throw new Exception("先查看反馈预览，再确认发布。");
    String token=token();JSONObject payload=new JSONObject().put("title",preview.getString("title")).put("body",preview.getString("body"));
    box.put("state","inFlight").put("attemptedAt",System.currentTimeMillis());box.remove("message");persist(current,box);
    Response response;try{response=transport.execute("POST",ISSUES_PATH,payload,token);}catch(IOException|RuntimeException e){return uncertain(current,box);}
    if(response.status==201){JSONObject receipt=receipt(response.body);if(receipt!=null){box.put("state","submitted").put("receipt",receipt).put("message","反馈发布好了，谢谢你告诉我。");persist(current,box);return publicState();}return uncertain(current,box);}
    if(response.status>=400&&response.status<500&&response.status!=408){box.put("state","ready").put("message",responseMessage(response));persist(current,box);throw new Exception(responseMessage(response));}
    return uncertain(current,box);
  }
  private JSONObject uncertain(JSONObject current,JSONObject box)throws Exception {box.put("state","uncertain").put("message",uncertainMessage());persist(current,box);return publicState();}
  private static String uncertainMessage(){return "GitHub 可能已经收到这条反馈，这次还没拿到确认。先核对发布结果，避免重复发送。";}
  private JSONObject check(JSONObject args)throws Exception {
    JSONObject current=draft(),box=outbox();if(current==null||!current.getString("id").equals(string(args,"draftId","")))throw new Exception("这份反馈草稿暂时没找到，请重新打开反馈。");
    if(box.optString("state").equals("submitted"))return publicState();if(!box.optString("state").equals("uncertain"))return publicState().put("message","这条反馈还没有发布，可以先查看预览。");
    String token=token(),marker=marker(current.getString("id"));
    for(int page=1;page<=5;page++){
      Response response;try{response=transport.execute("GET",ISSUES_PATH+"?state=all&sort=created&direction=desc&per_page=100&page="+page,null,token);}catch(IOException|RuntimeException e){return publicState().put("message","这次没能核对发布结果，草稿还在，可以稍后再查。");}
      if(response.status!=200)return publicState().put("message",responseMessage(response));JSONArray issues;try{issues=new JSONArray(response.body);}catch(JSONException e){return publicState().put("message","GitHub 的回应暂时没读完整，草稿还在，可以稍后再查。");}
      for(int i=0;i<issues.length();i++){JSONObject issue=issues.optJSONObject(i);if(issue==null||issue.has("pull_request")||!issue.optString("body").contains(marker))continue;JSONObject receipt=receipt(issue.toString());if(receipt!=null){box.put("state","submitted").put("receipt",receipt).put("message","找到了，这条反馈已经发布成功。");persist(current,box);return publicState();}}
      if(issues.length()<100)break;
    }
    return publicState().put("message","暂时没查到这条反馈，GitHub 可能还没刷新。草稿替你留着，可以稍后再核对。");
  }
  private JSONObject connection()throws Exception {
    try{Response response=transport.execute("GET",REPOSITORY_PATH,null,token());if(response.status==200)return publicState().put("ok",true).put("message","GitHub 连上了，仓库可以访问。正式发布时会检查创建 Issue 的权限。");return publicState().put("ok",false).put("message",responseMessage(response));}
    catch(IOException|RuntimeException e){return publicState().put("ok",false).put("message","这次没能连上 GitHub，设置还在，可以稍后再试。");}
  }
  private String token()throws Exception {String token=settings.secret("githubToken");if(token.isEmpty())throw new Exception("先在反馈连接里填写 GitHub Token，再发布到仓库。");return token;}
  private static JSONObject receipt(String body){try{JSONObject issue=new JSONObject(body);if(issue.has("pull_request")||!(issue.opt("number") instanceof Number))return null;long number=issue.getLong("number");String url=issue.getString("html_url");if(number<=0||!url.equals("https://github.com/"+REPO+"/issues/"+number))return null;return new JSONObject().put("number",number).put("url",url);}catch(Exception e){return null;}}
  private static String responseMessage(Response response){
    if(response.status==401)return "GitHub Token 没有通过验证，请检查后再试。";
    if(response.status==403||response.status==429){if(response.header("X-RateLimit-Remaining").equals("0")||!response.header("Retry-After").isEmpty()||response.body.toLowerCase(Locale.ROOT).contains("rate limit"))return "GitHub 现在限制了请求次数，草稿还在，可以稍后再试。";return "这个 Token 暂时不能创建或读取 Issue，请检查仓库的 Issues 读写权限。";}
    if(response.status==404)return "这个 Token 暂时不能访问小禾仓库，请检查所选仓库和权限。";
    if(response.status==422)return "GitHub 没有接受这份反馈，可以检查标题与内容后再试。";
    return "GitHub 这次没有完成操作，草稿仍保存在手机里。";
  }
  private String assistantId(String requested)throws Exception {String safe=redact(requested);if(!safe.matches("[a-zA-Z0-9_-]{8,80}"))return "";JSONObject message=Store.get(context).one("messages",safe);return message!=null&&message.optString("role").equals("assistant")?safe:"";}
  private JSONObject selectedMessage(JSONObject draft)throws Exception {String id=draft.optString("messageId");if(id.isEmpty())return null;JSONObject message=Store.get(context).one("messages",id);return message!=null&&message.optString("role").equals("assistant")?message:null;}
  private static String marker(String id){return "<!-- xiaohe-feedback:"+id+" -->";}
  private static String string(JSONObject value,String key,String fallback)throws Exception {Object raw=value.opt(key);if(raw==null||raw==JSONObject.NULL)return fallback;if(!(raw instanceof String))throw new Exception("反馈标题和内容请使用文字填写。");return (String)raw;}
  private static boolean option(JSONObject value,String key,boolean fallback)throws Exception {Object raw=value.opt(key);if(raw==null||raw==JSONObject.NULL)return fallback;if(!(raw instanceof Boolean))throw new Exception("请在反馈里明确选择是否附上这轮对话。");return (Boolean)raw;}
  private static String cut(String text,int max){int count=text.codePointCount(0,text.length());return count<=max?text:text.substring(0,text.offsetByCodePoints(0,max-1))+"…";}
  private static String hash(String title,String body)throws Exception {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(new JSONArray().put(title).put(body).toString().getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte value:bytes)out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();}
  private String redact(String text)throws Exception {String safe=text==null?"":text;for(String key:new String[]{"apiKey","asrKey","githubToken"}){String secret=settings.secret(key);if(!secret.isEmpty())safe=safe.replace(secret,HIDDEN);}safe=TOKEN_PATTERN.matcher(safe).replaceAll(HIDDEN);safe=BEARER_PATTERN.matcher(safe).replaceAll("$1"+HIDDEN);return safe.replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]"," ");}
  private void sanitizeStored()throws Exception {
    JSONObject draft=draft(),box=outbox();if(draft==null)return;boolean changed=false;
    for(String key:new String[]{"title","description"}){String value=draft.optString(key),safe=redact(value);if(!value.equals(safe)){draft.put(key,safe);changed=true;}}
    JSONObject preview=box.optJSONObject("preview");if(preview!=null){String title=redact(preview.optString("title")),body=redact(preview.optString("body"));if(!title.equals(preview.optString("title"))||!body.equals(preview.optString("body"))){preview.put("title",title).put("body",body).put("hash",hash(title,body)).put("redacted",true);changed=true;}}
    if(changed){box.put("redacted",true);persist(draft,box);}
  }
}
