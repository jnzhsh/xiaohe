package app.xiaohe.assistant;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.security.*;
import java.net.URI;
import org.json.*;

public final class Settings {
  private final SharedPreferences prefs;
  private static final String ALIAS="xiaohe-api-keys-v1";
  private static final Object KEY_LOCK=new Object();
  public Settings(Context c){prefs=c.getSharedPreferences("settings",0);}
  public String get(String k,String d){return prefs.getString(k,d);}
  public boolean flag(String k,boolean d){return prefs.getBoolean(k,d);}
  public boolean connected(){return !get("baseUrl","").isEmpty()&&!get("model","").isEmpty();}
  private SecretKey key() throws Exception {
    synchronized(KEY_LOCK){KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
      if(!ks.containsAlias(ALIAS)){KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();}
      return ((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS,null)).getSecretKey();}
  }
  public String secret(String field) throws Exception {
    String saved=get(field,"");if(saved.isEmpty())return "";String[] parts=saved.split(":",2);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));return new String(c.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),"UTF-8");
  }
  private String encrypt(String text)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());return Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(text.getBytes("UTF-8")),Base64.NO_WRAP);}
  public void saveGithubToken(String token,boolean clear)throws Exception {
    SharedPreferences.Editor editor=prefs.edit();
    if(clear)editor.remove("githubToken");
    else if(token!=null&&!token.trim().isEmpty()){
      String value=token.trim();if(value.length()>4096||value.matches("(?s).*[\\s\\p{Cntrl}].*"))throw new Exception("请只填写 GitHub Token 本身，不带换行或空格。");
      editor.putString("githubToken",encrypt(value));
    }
    if(!editor.commit())throw new Exception("连接设置没有存好，请检查手机剩余空间。");
  }
  public JSONObject publicValues() throws Exception {
    JSONObject o=new JSONObject();for(String k:new String[]{"baseUrl","model","asrUrl","asrModel"})o.put(k,get(k,k.equals("asrModel")?"whisper-1":""));o.put("hasKey",!get("apiKey","").isEmpty());o.put("hasAsrKey",!get("asrKey","").isEmpty());o.put("vision",flag("vision",false));o.put("localOcr",flag("localOcr",true));o.put("connected",connected());return o;
  }
  public synchronized void save(JSONObject o)throws Exception {
    SharedPreferences.Editor e=prefs.edit();
    for(String k:new String[]{"baseUrl","asrUrl"})if(o.has(k)){String value=o.optString(k).trim().replaceAll("/+$","");if(!value.isEmpty())validateUrl(value);String old=get(k,"");if(!service(old).equals(service(value))){String secret=k.equals("baseUrl")?"apiKey":"asrKey";e.remove(secret);}e.putString(k,value);}
    for(String k:new String[]{"model","asrModel"})if(o.has(k))e.putString(k,o.optString(k).trim());
    for(String k:new String[]{"vision","localOcr"})if(o.has(k))e.putBoolean(k,o.optBoolean(k));
    for(String k:new String[]{"apiKey","asrKey"}){if(o.optBoolean("clear_"+k))e.remove(k);else if(o.has(k)&&!o.optString(k).trim().isEmpty()){String value=o.optString(k).trim();if(value.contains("\n")||value.contains("\r"))throw new Exception("API 密钥应是单行字符串，请去掉换行");e.putString(k,encrypt(value));}}if(!e.commit())throw new Exception("设置没有写入成功，请检查本机剩余空间");
  }
  public static String host(String url){try{return new URI(url).getHost()==null?"":new URI(url).getHost();}catch(Exception e){return "";}}
  public static String service(String url){try{URI u=new URI(url);int port=u.getPort()<0?("https".equalsIgnoreCase(u.getScheme())?443:80):u.getPort();return u.getScheme().toLowerCase(java.util.Locale.ROOT)+"://"+u.getHost().toLowerCase(java.util.Locale.ROOT)+":"+port;}catch(Exception e){return "";}}
  public static void validateUrl(String u)throws Exception {URI uri=new URI(u);String scheme=uri.getScheme(),host=uri.getHost();if(host==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new Exception("接口地址应为不带账号、查询参数和片段的基础地址");boolean local=host.equals("localhost")||host.equals("127.0.0.1")||host.equals("::1")||host.startsWith("192.168.")||host.startsWith("10.")||host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*");if(!"https".equals(scheme)&&!("http".equals(scheme)&&local))throw new Exception("外网接口请使用 HTTPS；本地或局域网接口可以使用 HTTP");}
}
