package app.xiaohe.assistant;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import org.json.*;
public final class AttachmentProvider extends ContentProvider {
  public boolean onCreate(){return true;}
  public String getType(Uri u){try{return Store.get(getContext()).one("files",u.getLastPathSegment()).optString("mime","application/octet-stream");}catch(Exception e){return "application/octet-stream";}}
  public ParcelFileDescriptor openFile(Uri u,String mode)throws FileNotFoundException {if(!"r".equals(mode))throw new FileNotFoundException("只读附件");try{Store s=Store.get(getContext());if(s.one("files",u.getLastPathSegment())==null)throw new FileNotFoundException();return ParcelFileDescriptor.open(s.filePath(u.getLastPathSegment()),ParcelFileDescriptor.MODE_READ_ONLY);}catch(Exception e){throw new FileNotFoundException("附件不存在");}}
  public Cursor query(Uri u,String[] projection,String sel,String[] args,String sort){MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});try{JSONObject f=Store.get(getContext()).one("files",u.getLastPathSegment());if(f!=null)c.addRow(new Object[]{f.optString("name"),f.optLong("size")});}catch(Exception ignored){}return c;}
  public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
