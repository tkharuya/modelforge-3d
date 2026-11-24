package jp.haruya.modelforge;

import android.content.*;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

final class AppCore {
  private static AppCore instance;
  static synchronized AppCore get(Context context){if(instance==null)instance=new AppCore(context.getApplicationContext());return instance;}
  final Context context;
  final ExecutorService io=Executors.newCachedThreadPool();
  final File projects,refs;
  AuthClient auth;
  private String startupError;
  private final AtomicFile jobFile;
  private JSONObject job;
  private Net.Cancel cancel;
  volatile boolean working;
  AppCore(Context context){
    this.context=context;projects=new File(context.getFilesDir(),"projects");refs=new File(context.getFilesDir(),"references");projects.mkdirs();refs.mkdirs();jobFile=new AtomicFile(new File(context.getFilesDir(),"generation.json"));
    try{auth=new AuthClient(context);}catch(Exception e){startupError="認証情報を開けませんでした: "+friendly(e);}
    try{if(jobFile.getBaseFile().exists()){job=new JSONObject(new String(jobFile.readFully(),StandardCharsets.UTF_8));if(Arrays.asList("running","queued").contains(job.optString("status"))){job.put("status","interrupted").put("error","アプリのプロセス終了により生成が中断されました。最後の完成モデルは保存されています。指示を確認して再実行できます。").put("ack",false);writeJob();}}}catch(Exception e){startupError="生成記録を読み込めませんでした: "+friendly(e);}
  }
  static String friendly(Throwable e){String m=e.getMessage();if(e instanceof java.net.UnknownHostException)return "インターネットに接続できません。通信状態を確認してください。";if(e instanceof java.net.SocketTimeoutException)return "応答待ちがタイムアウトしました。最後に完成した形状は保持されています。";return m==null?e.getClass().getSimpleName():m;}
  static String validId(String id)throws IOException{if(id==null||!id.matches("[A-Za-z0-9_-]{1,100}"))throw new IOException("不正なファイル識別子です");return id;}
  static void atomicWrite(AtomicFile file,String content)throws Exception{FileOutputStream out=null;try{out=file.startWrite();out.write(content.getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}}
  synchronized void saveProject(JSONObject p)throws Exception{
    String id=validId(p.getString("id"));if(p.toString().length()>32*1024*1024)throw new IOException("プロジェクトが大きすぎて保存できません。JSONを書き出し、別プロジェクトとして続けてください。");atomicWrite(new AtomicFile(new File(projects,id+".json")),p.toString());
  }
  synchronized JSONObject loadProject(String id)throws Exception{AtomicFile f=new AtomicFile(new File(projects,validId(id)+".json"));if(!f.getBaseFile().exists())throw new IOException("プロジェクトが見つかりません");return new JSONObject(new String(f.readFully(),StandardCharsets.UTF_8));}
  synchronized JSONArray listProjects()throws Exception{
    List<JSONObject> list=new ArrayList<>();File[] files=projects.listFiles();if(files!=null)for(File f:files)if(f.getName().endsWith(".json")){try{JSONObject p=new JSONObject(new String(new AtomicFile(f).readFully(),StandardCharsets.UTF_8));list.add(new JSONObject().put("id",p.getString("id")).put("title",p.optString("title","無題のプロジェクト")).put("updated",p.optLong("updated",f.lastModified())));}catch(Exception ignored){}}
    Collections.sort(list,(a,b)->Long.compare(b.optLong("updated"),a.optLong("updated")));return new JSONArray(list);
  }
  synchronized void deleteProject(String id)throws Exception{if(working)throw new IOException("生成を停止してから削除してください");new AtomicFile(new File(projects,validId(id)+".json")).delete();}
  synchronized JSONObject status()throws Exception{
    JSONObject a=auth==null?new JSONObject().put("profiles",new JSONArray()).put("message",startupError):auth.state();JSONObject j=JSONObject.NULL.equals(job)?null:job;
    JSONObject safe=null;
    if(j!=null&&!j.optBoolean("ack")){safe=new JSONObject(j.toString());if(!"completed".equals(j.optString("status")))safe.remove("output");}
    return new JSONObject().put("auth",a).put("job",safe==null?JSONObject.NULL:safe);
  }
  synchronized JSONObject queue(JSONObject request)throws Exception{
    if(auth==null)throw new IOException(startupError);if(working||auth.signingIn)throw new IOException("現在の処理が終わるまでお待ちください");
    String id=validId(request.getString("projectId"));loadProject(id);String model=request.optString("model"),prompt=request.optString("prompt");if(model.isEmpty()||prompt.trim().isEmpty())throw new IOException("モデルと指示を指定してください");
    JSONObject state=auth.state();JSONArray profiles=state.getJSONArray("profiles");boolean allowed=false;for(int i=0;i<profiles.length();i++)if(profiles.getJSONObject(i).getString("id").equals(state.optString("active")))allowed=profiles.getJSONObject(i).optBoolean("sharing");if(!allowed)throw new IOException("ChatGPTに接続し、プランの利用を許可してください");
    job=new JSONObject().put("id",UUID.randomUUID().toString()).put("projectId",id).put("prompt",prompt).put("request",request).put("status","queued").put("chars",0).put("created",System.currentTimeMillis()).put("ack",false);writeJob();working=true;cancel=new Net.Cancel();return new JSONObject(job.toString());
  }
  synchronized void writeJob()throws Exception{if(job!=null)atomicWrite(jobFile,job.toString());}
  synchronized void acknowledge(String id)throws Exception{if(job!=null&&job.optString("id").equals(id)&&!working){job.put("ack",true);writeJob();}}
  synchronized void cancelJob(){if(cancel!=null)cancel.cancel();if(job!=null&&working){try{boolean queued="queued".equals(job.optString("status"));job.put("status","cancelled").put("ack",false);writeJob();if(queued){working=false;cancel=null;}}catch(Exception ignored){}}}
  void generate(){
    JSONObject request;Net.Cancel control;
    synchronized(this){if(job==null||!"queued".equals(job.optString("status")))return;request=job.optJSONObject("request");control=cancel;try{job.put("status","running");writeJob();}catch(Exception e){finish("failed",null,friendly(e));return;}}
    try{
      String instructions=Net.read(context.getAssets().open("model-prompt.txt"),1024*1024);
      JSONObject contextData=new JSONObject().put("request",request.getString("prompt")).put("current_design",request.opt("design")).put("current_is_sample",request.optBoolean("currentIsSample")).put("recent_edits",request.optJSONArray("history"));
      JSONArray content=new JSONArray().put(new JSONObject().put("type","input_text").put("text",contextData.toString()));
      JSONArray images=request.optJSONArray("refs");if(images!=null)for(int i=0;i<images.length();i++){
        control.check();File f=new File(refs,validId(images.getString(i))+".jpg");if(!f.isFile())throw new IOException("参考画像が見つかりません。画像を追加し直してください。");
        byte[] bytes;try(FileInputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);bytes=out.toByteArray();}
        content.put(new JSONObject().put("type","input_image").put("detail","auto").put("image_url","data:image/jpeg;base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP)));
      }
      JSONObject body=new JSONObject().put("model",request.getString("model")).put("instructions",instructions).put("store",false).put("stream",true).put("input",new JSONArray().put(new JSONObject().put("role","user").put("content",content)));
      Net.Progress progress=chars->{synchronized(AppCore.this){try{if(job!=null)job.put("chars",chars);}catch(Exception ignored){}}};
      String output;
      try{output=Net.stream(auth.token(false),body,control,progress);}catch(Net.HttpError e){if(e.status!=401)throw e;control.check();output=Net.stream(auth.token(true),body,control,progress);}
      control.check();finish("completed",output,null);
    }catch(Exception e){finish(control!=null&&control.cancelled?"cancelled":"failed",null,friendly(e));}
  }
  private synchronized void finish(String status,String output,String error){
    try{job.put("status",status).put("finished",System.currentTimeMillis());if(output!=null)job.put("output",output);if(error!=null)job.put("error",error);writeJob();}catch(Exception e){try{job.put("status","failed").put("error","生成結果を保存できませんでした: "+friendly(e));}catch(Exception ignored){}}finally{working=false;cancel=null;}
  }
}
