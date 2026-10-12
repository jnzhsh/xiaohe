package app.xiaohe.assistant;

import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.media.*;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.net.Uri;
import android.util.Base64;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

public final class MediaProcessor {
  private final Context context;private final Store store;
  public MediaProcessor(Context c){context=c;store=Store.get(c);}
  public JSONObject copy(Uri uri)throws Exception {if(!"content".equals(uri.getScheme()))throw new IOException("请通过系统文件选择器或分享入口提供附件");String id=UUID.randomUUID().toString(),name="收到的文件",mime=context.getContentResolver().getType(uri);long size=0;
    try(Cursor c=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}catch(Exception ignored){}
    if(mime==null)mime="application/octet-stream";File target=store.filePath(id);MessageDigest digest=MessageDigest.getInstance("SHA-256");
    try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(target)){if(in==null)throw new IOException("无法读取附件");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){out.write(b,0,n);digest.update(b,0,n);size+=n;}}
    catch(Exception e){target.delete();throw e;}
    JSONObject f=new JSONObject().put("id",id).put("created",System.currentTimeMillis()).put("name",name).put("mime",mime).put("size",size).put("sha256",hex(digest.digest())).put("text","").put("processingNote","原件已保存在本机，尚未解析");store.put("files",f);return f;
  }
  public JSONObject recorded(File recording)throws Exception {String id=UUID.randomUUID().toString();File target=store.filePath(id);try(InputStream in=new FileInputStream(recording);OutputStream out=new FileOutputStream(target)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}JSONObject f=new JSONObject().put("id",id).put("created",System.currentTimeMillis()).put("name","语音-"+System.currentTimeMillis()+".m4a").put("mime","audio/mp4").put("size",target.length()).put("sha256",hash(target)).put("text","").put("processingNote","语音原件已保存，尚未转写");store.put("files",f);recording.delete();return f;}
  public JSONObject prepare(JSONArray ids)throws Exception {JSONArray frames=new JSONArray(),notes=new JSONArray();Settings settings=new Settings(context);
    for(int i=0;i<ids.length();i++){JSONObject f=store.one("files",ids.getString(i));if(f==null)continue;String mime=f.optString("mime"),name=f.optString("name").toLowerCase(Locale.ROOT),note="";File path=store.filePath(f.getString("id"));
      try {
        if(mime.startsWith("image/")){BitmapFactory.Options opt=new BitmapFactory.Options();opt.inJustDecodeBounds=true;BitmapFactory.decodeFile(path.getAbsolutePath(),opt);opt.inSampleSize=Math.max(1,Math.max(opt.outWidth,opt.outHeight)/1800);opt.inJustDecodeBounds=false;Bitmap b=BitmapFactory.decodeFile(path.getAbsolutePath(),opt);if(b==null)throw new IOException("图片格式无法解码");frames.put(frame(b,f.getString("id"),"图片："+f.optString("name")));note="图片文字将由本机OCR识别；原图已保留";}
        else if(mime.equals("application/pdf")||name.endsWith(".pdf")){try(ParcelFileDescriptor pfd=ParcelFileDescriptor.open(path,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(pfd)){int total=renderer.getPageCount();for(int p=0;p<Math.min(total,5);p++)try(PdfRenderer.Page page=renderer.openPage(p)){float scale=1200f/page.getWidth();Bitmap b=Bitmap.createBitmap(1200,Math.max(1,(int)(page.getHeight()*scale)),Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);frames.put(frame(b,f.getString("id"),"PDF第"+(p+1)+"页"));}note="本次识别前"+Math.min(total,5)+"页，共"+total+"页；完整PDF保存在本机";}}
        else if(mime.startsWith("video/")){MediaMetadataRetriever r=new MediaMetadataRetriever();try{r.setDataSource(path.getAbsolutePath());long duration=Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));for(int n=0;n<7;n++){long ms=duration*n/7;Bitmap b=r.getFrameAtTime(ms*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);if(b!=null)frames.put(frame(b,f.getString("id"),"视频 "+(ms/1000)+" 秒的抽样画面"));}note="在视频时长内抽样7个画面；不是逐帧解析";}finally{r.release();}
          if(!settings.get("asrUrl","").isEmpty()){File audio=extractAudio(path);try{f.put("text",new AiEngine(context).transcribe(audio,"video-audio.m4a"));note+="；声音已通过独立接口转写";}finally{audio.delete();}}else note+="；声音未转写（未连接独立转写接口）";
        }
        else if(mime.startsWith("audio/")){f.put("text",new AiEngine(context).transcribe(path,name.contains(".")?name:"voice.m4a"));note="语音已通过独立接口转写，原件仍保存在本机";}
        else if(name.endsWith(".docx")||name.endsWith(".pptx")||name.endsWith(".xlsx")){JSONObject result=officeText(path);f.put("text",result.getString("text")).put("textTruncated",result.getBoolean("limited"));note=result.getBoolean("limited")?"本次提取了前一部分文档文字，已读出的文字完整保留；完整文档仍在本机，嵌入图片和排版未解析":"已在本机提取文档文字，嵌入图片和排版未解析";}
        else if(mime.startsWith("text/")||name.matches(".*\\.(txt|md|csv|json|html|htm|log)$")){f.put("text",readText(path,2*1024*1024));note=path.length()>2*1024*1024?"本次读取前2MB文字，完整文件保存在本机":"文件文字已在本机提取";}
        else note="已保留原件；此格式尚不能自动读取，可补充一段说明";
      }catch(Exception e){note="原件已保留，解析未完成："+Store.shorten(e.getMessage()==null?"格式不支持":e.getMessage(),180);}
      f.put("processingNote",note);store.put("files",f);notes.put(new JSONObject().put("id",f.getString("id")).put("note",note));
    }return new JSONObject().put("frames",frames).put("notes",notes);
  }
  private JSONObject frame(Bitmap bitmap,String id,String label)throws Exception {Bitmap use=bitmap;if(Math.max(bitmap.getWidth(),bitmap.getHeight())>1800){float s=1800f/Math.max(bitmap.getWidth(),bitmap.getHeight());use=Bitmap.createScaledBitmap(bitmap,Math.max(1,(int)(bitmap.getWidth()*s)),Math.max(1,(int)(bitmap.getHeight()*s)),true);}ByteArrayOutputStream out=new ByteArrayOutputStream();use.compress(Bitmap.CompressFormat.JPEG,86,out);JSONObject result=new JSONObject().put("sourceId",id).put("label",label).put("dataUrl","data:image/jpeg;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP));if(use!=bitmap)use.recycle();bitmap.recycle();return result;}
  private File extractAudio(File source)throws Exception {File output=File.createTempFile("xiaohe-audio-",".m4a",context.getCacheDir());MediaExtractor extractor=new MediaExtractor();MediaMuxer muxer=null;boolean started=false;
    try{extractor.setDataSource(source.getAbsolutePath());int track=-1;MediaFormat format=null;for(int i=0;i<extractor.getTrackCount();i++){MediaFormat f=extractor.getTrackFormat(i);if(f.getString(MediaFormat.KEY_MIME).startsWith("audio/")){track=i;format=f;break;}}if(track<0)throw new IOException("视频没有声音轨道");extractor.selectTrack(track);muxer=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);int target=muxer.addTrack(format);muxer.start();started=true;ByteBuffer buffer=ByteBuffer.allocate(2*1024*1024);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();while(true){buffer.clear();int size=extractor.readSampleData(buffer,0);if(size<0)break;info.set(0,size,extractor.getSampleTime(),extractor.getSampleFlags());muxer.writeSampleData(target,buffer,info);extractor.advance();}return output;
    }catch(Exception e){output.delete();throw e;}finally{extractor.release();if(muxer!=null){if(started)try{muxer.stop();}catch(Exception ignored){}muxer.release();}}
  }
  private static JSONObject officeText(File file)throws Exception {StringBuilder text=new StringBuilder();long[] xmlBytes={0};try(ZipFile zip=new ZipFile(file)){Enumeration<? extends ZipEntry> entries=zip.entries();while(entries.hasMoreElements()){ZipEntry e=entries.nextElement();if(!e.getName().matches("(word/document|ppt/slides/slide[0-9]+|xl/sharedStrings)\\.xml"))continue;
      try(InputStream in=new FilterInputStream(zip.getInputStream(e)){
        private void count(int n)throws IOException{if(n>0&&(xmlBytes[0]+=n)>16L*1024*1024)throw new IOException("文档文字结构超过本次解析上限，完整原件仍保留");}
        @Override public int read()throws IOException{int value=super.read();count(value<0?0:1);return value;}
        @Override public int read(byte[] b,int off,int len)throws IOException{int n=super.read(b,off,len);count(n);return n;}
      }){XmlPullParser p=Xml.newPullParser();p.setInput(in,"UTF-8");int event;while((event=p.next())!=XmlPullParser.END_DOCUMENT){if(event==XmlPullParser.START_TAG&&"t".equals(p.getName()))text.append(p.nextText()).append(' ');if(text.length()>1000000)return new JSONObject().put("text",text.toString()).put("limited",true);}}
    }}return new JSONObject().put("text",text.toString()).put("limited",false);}
  public static String readText(File file,int max)throws Exception {try(InputStream in=new FileInputStream(file)){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while(b.size()<max&&(n=in.read(buf,0,Math.min(buf.length,max-b.size())))!=-1)b.write(buf,0,n);byte[] bytes=b.toByteArray();int end=bytes.length;
    // A byte limit must not turn the final half of a UTF-8 character into a replacement character.
    if(file.length()>end&&end>0){int start=end-1;while(start>=0&&(bytes[start]&0xc0)==0x80)start--;if(start>=0){int lead=bytes[start]&255,needed=(lead&0xe0)==0xc0?2:(lead&0xf0)==0xe0?3:(lead&0xf8)==0xf0?4:1;if(end-start<needed)end=start;}}
    String text=new String(bytes,0,end,StandardCharsets.UTF_8);return text.startsWith("\uFEFF")?text.substring(1):text;}}
  public static String hash(File file)throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}return hex(digest.digest());}
  private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
  public static String fetchLink(String value)throws Exception {URL u=new URL(value);if(!u.getProtocol().equals("https")&&!u.getProtocol().equals("http"))throw new IOException("链接协议不支持");HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(10000);c.setReadTimeout(12000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","Mozilla/5.0 Xiaohe/1.0");try{int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("网页返回HTTP "+code+"，可能需要登录或不允许读取");String type=c.getContentType();if(type==null||(!type.contains("html")&&!type.startsWith("text/")))throw new IOException("链接不是可读取的网页文字");ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] buf=new byte[8192];int n;while(b.size()<1024*1024&&(n=in.read(buf,0,Math.min(buf.length,1024*1024-b.size())))!=-1)b.write(buf,0,n);}String html=b.toString("UTF-8").replaceAll("(?is)<(script|style|noscript)[^>]*>.*?</\\1>","").replaceAll("(?is)<[^>]+>"," ").replace("&nbsp;"," ").replace("&amp;","&");return html.replaceAll("\\s+"," ").trim();}finally{c.disconnect();}}
}
