package jp.haruya.modelforge;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.*;
import android.webkit.*;
import android.util.Base64;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

public final class MainActivity extends Activity {
  private WebView web;
  private AppCore core;
  private String pickerId;
  private int pickerType;
  private byte[] exportBytes;
  private static final String ORIGIN="https://modelforge.local";
  private static final int IMAGES=20,IMPORT=21,EXPORT=22;
  @Override public void onCreate(Bundle state){
    super.onCreate(state);getWindow().setStatusBarColor(Color.rgb(16,27,46));getWindow().setNavigationBarColor(Color.rgb(20,32,54));getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    core=AppCore.get(this);web=new WebView(this);setContentView(web);web.setBackgroundColor(Color.rgb(16,27,46));
    web.setOnApplyWindowInsetsListener((view,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets system=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(system.left,system.top,system.right,system.bottom);}return insets;});
    WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setMediaPlaybackRequiresUserGesture(true);s.setSupportMultipleWindows(false);s.setJavaScriptCanOpenWindowsAutomatically(false);
    web.addJavascriptInterface(new Bridge(),"Native");
    web.setWebViewClient(new WebViewClient(){
      @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest req){return localResponse(req.getUrl());}
      @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest req){return !req.getUrl().toString().startsWith(ORIGIN+"/");}
      @Override public void onReceivedSslError(WebView v,android.webkit.SslErrorHandler handler,android.net.http.SslError error){handler.cancel();}
      @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){new AlertDialog.Builder(MainActivity.this).setTitle("画面を再読み込みします").setMessage("最後に保存したモデルと生成処理は保持されています。").setPositiveButton("開き直す",(d,w)->recreate()).setCancelable(false).show();return true;}
    });
    web.setWebChromeClient(new WebChromeClient(){
      @Override public boolean onJsAlert(WebView v,String url,String message,JsResult result){new AlertDialog.Builder(MainActivity.this).setMessage(message).setPositiveButton("OK",(d,w)->result.confirm()).setOnCancelListener(d->result.cancel()).show();return true;}
      @Override public boolean onJsConfirm(WebView v,String url,String message,JsResult result){new AlertDialog.Builder(MainActivity.this).setMessage(message).setPositiveButton("実行",(d,w)->result.confirm()).setNegativeButton("キャンセル",(d,w)->result.cancel()).setOnCancelListener(d->result.cancel()).show();return true;}
      @Override public boolean onJsPrompt(WebView v,String url,String message,String value,JsPromptResult result){android.widget.EditText edit=new android.widget.EditText(MainActivity.this);edit.setText(value);new AlertDialog.Builder(MainActivity.this).setMessage(message).setView(edit).setPositiveButton("保存",(d,w)->result.confirm(edit.getText().toString())).setNegativeButton("キャンセル",(d,w)->result.cancel()).setOnCancelListener(d->result.cancel()).show();return true;}
    });
    web.loadUrl(ORIGIN+"/index.html");
  }
  private WebResourceResponse localResponse(Uri u){
    try{
      if(!"https".equals(u.getScheme())||!"modelforge.local".equals(u.getHost()))return blocked();
      String path=u.getPath();InputStream in;String mime;
      if(path!=null&&path.startsWith("/reference/")){String id=path.substring("/reference/".length());if(!id.endsWith(".jpg"))return blocked();AppCore.validId(id.substring(0,id.length()-4));in=new FileInputStream(new File(core.refs,id));mime="image/jpeg";}
      else{
        if(path==null||!path.matches("/[a-zA-Z0-9_.-]+"))return blocked();String name=path.substring(1);mime=name.endsWith(".js")?"text/javascript":name.endsWith(".css")?"text/css":name.endsWith(".wasm")?"application/wasm":name.endsWith(".html")?"text/html":"text/plain";in=getAssets().open(name);
      }
      Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","no-cache");headers.put("X-Content-Type-Options","nosniff");headers.put("Content-Security-Policy","default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; worker-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-src 'none'");
      return new WebResourceResponse(mime,mime.startsWith("text/")?"UTF-8":null,200,"OK",headers,in);
    }catch(Exception e){return blocked();}
  }
  private static WebResourceResponse blocked(){return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
  private void result(String id,Object result,String error){
    runOnUiThread(()->{if(isFinishing()||isDestroyed()||web==null)return;String json=result==null?"null":result instanceof JSONObject||result instanceof JSONArray?result.toString():JSONObject.quote(String.valueOf(result));web.evaluateJavascript("window.__nativeResult&&window.__nativeResult("+JSONObject.quote(id)+","+json+","+(error==null?"null":JSONObject.quote(error))+")",null);});
  }
  private void openBrowser(String url){runOnUiThread(()->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));}catch(Exception e){if(core.auth!=null)core.auth.cancelLogin();new AlertDialog.Builder(this).setMessage("ブラウザーを開けませんでした。Chromeなどを有効にしてください。").setPositiveButton("OK",null).show();}});}
  private void startWork(String action){runOnUiThread(()->{try{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},7);startForegroundService(new Intent(this,WorkService.class).setAction(action));}catch(Exception e){core.cancelJob();new AlertDialog.Builder(this).setMessage(AppCore.friendly(e)).setPositiveButton("OK",null).show();}});}
  final class Bridge {
    @JavascriptInterface public void call(String id,String action,String data){
      core.io.execute(()->{
        try{
          JSONObject d=new JSONObject(data);Object out=new JSONObject();
          switch(action){
            case "getStatus":out=core.status();break;
            case "listProjects":out=core.listProjects();break;
            case "loadProject":out=core.loadProject(d.getString("id"));break;
            case "saveProject":core.saveProject(d.getJSONObject("project"));break;
            case "deleteProject":core.deleteProject(d.getString("id"));break;
            case "models":
              if(core.auth==null)throw new IOException("認証情報を読み込めませんでした");
              JSONObject state=core.auth.state();boolean connected=false;JSONArray profiles=state.getJSONArray("profiles");for(int i=0;i<profiles.length();i++){JSONObject p=profiles.getJSONObject(i);if(p.getString("id").equals(state.optString("active"))&&p.optBoolean("sharing"))connected=true;}
              out=connected?core.auth.models():new JSONArray();break;
            case "startAuth":
              if(core.working)throw new IOException("生成処理中はアカウントを変更できません");if(core.auth==null)throw new IOException("認証情報を読み込めませんでした");if(core.auth.signingIn)throw new IOException("ログイン処理中です");
              core.io.execute(()->{try{core.auth.login(d.optBoolean("newAccount"),url->{startWork("auth");openBrowser(url);});}catch(Exception ignored){}});break;
            case "cancelAuth":if(core.auth!=null)core.auth.cancelLogin();break;
            case "switchAccount":if(core.working)throw new IOException("生成処理中はアカウントを変更できません");out=core.auth.select(d.getString("id"));break;
            case "logout":if(core.working)throw new IOException("生成を停止してからログアウトしてください");out=new JSONObject().put("message",core.auth.logout());break;
            case "startJob":out=core.queue(d);startWork("generate");break;
            case "cancelJob":core.cancelJob();break;
            case "ackJob":core.acknowledge(d.getString("id"));break;
            case "pickImages":runOnUiThread(()->pick(id,IMAGES,null));return;
            case "importProject":runOnUiThread(()->pick(id,IMPORT,null));return;
            case "exportFile":
              byte[] bytes=Base64.decode(d.getString("base64"),Base64.DEFAULT);if(bytes.length>120*1024*1024)throw new IOException("書き出しファイルが大きすぎます。曲面の細かさを下げてください。");
              runOnUiThread(()->{if(pickerId!=null){result(id,null,"ファイル操作を完了してください");return;}exportBytes=bytes;pick(id,EXPORT,d);});return;
            case "openUsage":openBrowser("https://chatgpt.com/settings/usage");break;
            default:throw new IOException("未対応の操作です");
          }result(id,out,null);
        }catch(Exception e){result(id,null,AppCore.friendly(e));}
      });
    }
  }
  private void pick(String id,int type,JSONObject data){
    if(pickerId!=null){result(id,null,"ファイル操作を完了してください");return;}
    try{Intent i=new Intent(type==EXPORT?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);
      if(type==IMAGES){i.setType("image/*");i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);}else if(type==IMPORT){i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain","application/octet-stream"});}else{i.setType(data.optString("mime","application/octet-stream"));String name=data.optString("name","model.stl").replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_");i.putExtra(Intent.EXTRA_TITLE,name);}
      pickerId=id;pickerType=type;startActivityForResult(i,type);
    }catch(Exception e){pickerId=null;exportBytes=null;result(id,null,AppCore.friendly(e));}
  }
  @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
    super.onActivityResult(requestCode,resultCode,data);if(requestCode!=pickerType||pickerId==null)return;String id=pickerId;pickerId=null;
    if(resultCode!=RESULT_OK||data==null){exportBytes=null;result(id,new JSONObject(),null);return;}
    final byte[] bytes=exportBytes;exportBytes=null;
    core.io.execute(()->{try{
      if(requestCode==EXPORT){Uri uri=data.getData();if(uri==null||bytes==null)throw new IOException("保存するデータが見つかりません");try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("保存先を開けませんでした");out.write(bytes);}result(id,new JSONObject().put("saved",true),null);}
      else if(requestCode==IMPORT){Uri uri=data.getData();if(uri==null)throw new IOException("ファイルが見つかりません");String text=Net.read(getContentResolver().openInputStream(uri),32*1024*1024);result(id,new JSONObject().put("text",text),null);}
      else{List<Uri> uris=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)uris.add(data.getData());JSONArray a=new JSONArray();for(Uri uri:uris)a.put(storeImage(uri));result(id,new JSONObject().put("refs",a),null);}
    }catch(Exception e){result(id,null,AppCore.friendly(e));}});
  }
  private JSONObject storeImage(Uri uri)throws Exception{
    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;try(InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,bounds);}if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("この画像形式を読み込めませんでした。JPEGまたはPNGを使用してください。");
    BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>3072)options.inSampleSize*=2;
    Bitmap bitmap;try(InputStream in=getContentResolver().openInputStream(uri)){bitmap=BitmapFactory.decodeStream(in,null,options);}if(bitmap==null)throw new IOException("画像を読み込めませんでした");
    int orientation=1;try(InputStream in=getContentResolver().openInputStream(uri)){orientation=new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}catch(Exception ignored){}
    Matrix matrix=new Matrix();switch(orientation){case 2:matrix.setScale(-1,1);break;case 3:matrix.setRotate(180);break;case 4:matrix.setScale(1,-1);break;case 5:matrix.setRotate(90);matrix.postScale(-1,1);break;case 6:matrix.setRotate(90);break;case 7:matrix.setRotate(270);matrix.postScale(-1,1);break;case 8:matrix.setRotate(270);break;}
    if(!matrix.isIdentity()){Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);if(rotated!=bitmap)bitmap.recycle();bitmap=rotated;}
    int longEdge=Math.max(bitmap.getWidth(),bitmap.getHeight());if(longEdge>1536){float scale=1536f/longEdge;Bitmap small=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*scale)),Math.max(1,Math.round(bitmap.getHeight()*scale)),true);if(small!=bitmap)bitmap.recycle();bitmap=small;}
    Bitmap flattened=Bitmap.createBitmap(bitmap.getWidth(),bitmap.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(flattened);canvas.drawColor(Color.WHITE);canvas.drawBitmap(bitmap,0,0,null);bitmap.recycle();
    String fid=UUID.randomUUID().toString(),name="参考画像";try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}catch(Exception ignored){}
    File f=new File(core.refs,fid+".jpg");try(OutputStream out=new FileOutputStream(f)){if(!flattened.compress(Bitmap.CompressFormat.JPEG,90,out))throw new IOException("画像を保存できませんでした");}finally{flattened.recycle();}
    return new JSONObject().put("id",fid).put("name",name).put("url",ORIGIN+"/reference/"+fid+".jpg");
  }
  @Override protected void onResume(){super.onResume();if(web!=null)web.onResume();}
  @Override protected void onPause(){if(web!=null)web.onPause();super.onPause();}
  @Override public void onBackPressed(){if(web!=null)web.evaluateJavascript("(()=>{const d=document.querySelector('dialog[open]');if(d){d.close();return true;}return false;})()",v->{if(!"true".equals(v))MainActivity.super.onBackPressed();});else super.onBackPressed();}
  @Override protected void onDestroy(){if(web!=null){web.removeJavascriptInterface("Native");web.destroy();web=null;}super.onDestroy();}
}
