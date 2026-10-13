package app.xiaohe.tests;

import android.app.Instrumentation;
import app.xiaohe.assistant.Feedback;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

/** Exercises the production HTTP implementation without a socket or a real GitHub request. */
public final class FeedbackTransportSmoke {
  private static final List<FakeConnection> opened=new ArrayList<>();
  private static final class FakeConnection extends HttpsURLConnection {
    final ByteArrayOutputStream sent=new ByteArrayOutputStream();final Map<String,String> properties=new HashMap<>();boolean disconnected;int status=302;
    FakeConnection(URL url){super(url);}
    @Override public void setRequestProperty(String key,String value){properties.put(key,value);}
    @Override public String getRequestProperty(String key){return properties.get(key);}
    @Override public OutputStream getOutputStream(){return sent;}
    @Override public int getResponseCode(){return status;}
    @Override public InputStream getInputStream(){return new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8));}
    @Override public InputStream getErrorStream(){return getInputStream();}
    @Override public Map<String,List<String>> getHeaderFields(){return Collections.singletonMap("Location",Collections.singletonList("https://untrusted.example/redirect-target"));}
    @Override public void disconnect(){disconnected=true;}
    @Override public boolean usingProxy(){return false;}
    @Override public void connect(){}
    @Override public String getCipherSuite(){return "SYNTHETIC_NO_NETWORK";}
    @Override public Certificate[] getLocalCertificates(){return null;}
    @Override public Certificate[] getServerCertificates(){return new Certificate[0];}
    @Override public Principal getPeerPrincipal(){return null;}
    @Override public Principal getLocalPrincipal(){return null;}
  }
  public static JSONObject run(Instrumentation test)throws Exception{
    // This mode runs in its own instrumentation process. Abort on any other
    // HTTPS host: synthetic fixture authorization can never reach the network.
    URL.setURLStreamHandlerFactory(protocol->protocol.equals("https")?new URLStreamHandler(){
      @Override protected URLConnection openConnection(URL url)throws IOException{if(!url.getHost().equals("api.github.com"))throw new IOException("Fixture forbids every external host");FakeConnection connection=new FakeConnection(url);opened.add(connection);return connection;}
    }:null);
    Feedback.HttpTransport transport=new Feedback.HttpTransport();JSONObject previewPayload=new JSONObject().put("title","合成反馈 😀").put("body","核对过的中文正文。\n<!-- xiaohe-feedback:00000000-0000-0000-0000-000000000001 -->");Feedback.Response response=transport.execute("POST",FeedbackSmoke.ISSUES,previewPayload,FeedbackSmoke.PAT);
    FeedbackSupport.check(response.status==302&&opened.size()==1,"A redirect is returned to the state machine rather than followed");FakeConnection connection=opened.get(0);FeedbackSupport.check(connection.getURL().toString().equals("https://api.github.com/repos/jnzhsh/xiaohe/issues")&&!connection.getInstanceFollowRedirects()&&connection.disconnected,"Production transport fixes HTTPS GitHub host/path, disables redirects, and releases connection");FeedbackSupport.check(connection.properties.get("Authorization").equals("Bearer "+FeedbackSmoke.PAT)&&connection.properties.get("Accept").equals("application/vnd.github+json")&&connection.properties.get("X-GitHub-Api-Version").equals("2022-11-28"),"Authorization and GitHub headers apply only to the fixed endpoint");FeedbackSupport.check(new JSONObject(connection.sent.toString("UTF-8")).toString().equals(previewPayload.toString()),"Production transport encodes the reviewed JSON payload as UTF-8");
    transport.execute("GET",FeedbackSmoke.REPO,null,FeedbackSmoke.PAT);FeedbackSupport.check(opened.size()==2&&opened.get(1).getRequestMethod().equals("GET")&&opened.get(1).sent.size()==0,"Connection check is a GET without a body or Issue creation");
    for(String path:new String[]{"https://evil.example/path","/repos/another/project/issues",FeedbackSmoke.ISSUES+"/77777",FeedbackSmoke.ISSUES+"?state=all&sort=created&direction=desc&per_page=100&page=6"}){int before=opened.size();boolean rejected=false;try{transport.execute("POST",path,previewPayload,FeedbackSmoke.PAT);}catch(IOException expected){rejected=true;}FeedbackSupport.check(rejected&&opened.size()==before,"Untrusted or unsupported endpoint is refused before opening a connection");}
    return new JSONObject().put("productionHttpTransportTested",true).put("actualNetworkRequests",0).put("fixedGithubHostAndRepository",true).put("authorizationHeadersValidated",true).put("redirectsDisabledAndAuthNeverForwarded",true).put("utf8PayloadExactlyPreserved",true).put("unsupportedUrlsRejectedBeforeConnection",true);
  }
}
