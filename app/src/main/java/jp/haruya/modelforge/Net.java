package jp.haruya.modelforge;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class Net {
  static final class Cancel {
    volatile boolean cancelled;
    volatile HttpURLConnection connection;
    void cancel(){cancelled=true;HttpURLConnection c=connection;if(c!=null)c.disconnect();}
    void check() throws IOException {if(cancelled)throw new IOException("処理を停止しました");}
  }
  interface Progress {void update(int chars);}
  static final class HttpError extends IOException {
    final int status;final String code;
    HttpError(int status,String code,String message){super(message);this.status=status;this.code=code;}
  }
  static String form(Map<String,String> p) throws Exception {
    StringBuilder out=new StringBuilder();for(Map.Entry<String,String> e:p.entrySet()){
      if(e.getValue()==null||e.getValue().isEmpty())continue;
      if(out.length()>0)out.append('&');out.append(URLEncoder.encode(e.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(e.getValue(),"UTF-8"));
    }return out.toString();
  }
  static Map<String,String> pairs(String...p){Map<String,String> r=new LinkedHashMap<>();for(int i=0;i<p.length;i+=2)r.put(p[i],p[i+1]);return r;}
  static String read(InputStream in,int limit) throws IOException {
    if(in==null)return "";try(InputStream source=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
      byte[] b=new byte[16384];int n;while((n=source.read(b))!=-1){if(out.size()+n>limit)throw new IOException("受信データが大きすぎます");out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
  }
  static HttpURLConnection connection(String url,String method,String token,String contentType,byte[] body,Cancel cancel,int timeout) throws Exception {
    if(cancel!=null)cancel.check();HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(30000);c.setReadTimeout(timeout);c.setInstanceFollowRedirects(false);c.setRequestMethod(method);c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","ModelForge3D/1.0 (Android)");
    if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);
    if(cancel!=null)cancel.connection=c;
    try{if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type",contentType);c.setFixedLengthStreamingMode(body.length);try(OutputStream out=c.getOutputStream()){out.write(body);}}
      if(cancel!=null)cancel.check();return c;
    }catch(Exception e){c.disconnect();if(cancel!=null)cancel.connection=null;throw e;}
  }
  static String request(String url,String method,String token,String contentType,String body) throws Exception {
    HttpURLConnection c=connection(url,method,token,contentType,body==null?null:body.getBytes(StandardCharsets.UTF_8),null,60000);
    try{int status=c.getResponseCode();String text=read(status>=400?c.getErrorStream():c.getInputStream(),8*1024*1024);if(status<200||status>=300)throw error(status,text);return text;}finally{c.disconnect();}
  }
  static JSONObject get(String url,String token) throws Exception{return new JSONObject(request(url,"GET",token,null,null));}
  static JSONObject postForm(String url,Map<String,String> params)throws Exception{return new JSONObject(request(url,"POST",null,"application/x-www-form-urlencoded",form(params)));}
  static HttpError error(int status,String raw){
    String code="",message="";try{JSONObject root=new JSONObject(raw);Object e=root.opt("error");if(e instanceof JSONObject){code=((JSONObject)e).optString("code");message=((JSONObject)e).optString("message");}else{code=root.optString("error");message=root.optString("error_description");}}catch(Exception ignored){}
    if(code.contains("subscription_sharing_usage_limit")||status==429)message="ChatGPTの利用上限に達したか、リクエストが混み合っています。利用状況を確認して、時間を置いて再実行してください。";
    else if(code.contains("subscription_sharing_usage_unavailable"))message="このアカウントでは現在ChatGPTプランの共有利用ができません。ChatGPT側の利用設定を確認してください。";
    else if(status==401||code.equals("invalid_grant"))message="ChatGPTへの接続が切れました。同じアカウントで再ログインしてください。";
    else if(status==403)message="この操作は許可されていません。ChatGPTプランの利用許可と、選択したモデルを確認してください。";
    else if(status>=500)message="OpenAI側でエラーが発生しました。時間を置いて再実行してください。";
    if(message.isEmpty())message="通信エラー (HTTP "+status+(code.isEmpty()?"":" / "+code)+")";
    if(message.length()>1800)message=message.substring(0,1800);
    return new HttpError(status,code,message);
  }
  static String stream(String token,JSONObject request,Cancel cancel,Progress progress)throws Exception {
    return streamAt("https://api.openai.com/v1/responses",token,request,cancel,progress);
  }
  // Package-private transport seam used by the local protocol tests. The app only calls stream().
  static String streamAt(String endpoint,String token,JSONObject request,Cancel cancel,Progress progress)throws Exception {
    HttpURLConnection c=connection(endpoint,"POST",token,"application/json",request.toString().getBytes(StandardCharsets.UTF_8),cancel,240000);
    try{
      int status=c.getResponseCode();if(status<200||status>=300)throw error(status,read(c.getErrorStream(),2*1024*1024));
      if(c.getContentType()==null||!c.getContentType().contains("text/event-stream"))throw new IOException("AIからストリーミング形式の応答を取得できませんでした");
      StringBuilder result=new StringBuilder(),data=new StringBuilder();long lastProgress=0;
      try(BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
        String line;
        while((line=reader.readLine())!=null){
          cancel.check();
          if(line.isEmpty()){
            if(data.length()==0)continue;String payload=data.toString();data.setLength(0);if(payload.equals("[DONE]"))break;
            JSONObject event=new JSONObject(payload);String type=event.optString("type");
            if(type.equals("response.output_text.delta")){result.append(event.optString("delta"));if(result.length()>20*1024*1024)throw new IOException("設計結果が端末で処理できる大きさを超えました");if(System.currentTimeMillis()-lastProgress>300){progress.update(result.length());lastProgress=System.currentTimeMillis();}}
            else if(type.equals("response.completed")){
              JSONObject response=event.optJSONObject("response");String full=outputText(response);if(!full.isEmpty()){result.setLength(0);result.append(full);}cancel.check();if(result.toString().trim().isEmpty())throw new IOException("AIは完了しましたが設計データが空でした");return result.toString();
            }else if(type.equals("response.failed")){JSONObject response=event.optJSONObject("response");throw error(400,response==null?event.toString():response.toString());}
            else if(type.equals("response.incomplete"))throw new IOException("AIの応答が完了前に終了しました。最後の完成モデルは保持されています。指示を調整して再実行してください。");
            else if(type.equals("error"))throw error(400,new JSONObject().put("error",event).toString());
          }else if(line.startsWith("data:")){if(data.length()>0)data.append('\n');data.append(line.substring(5).trim());}
        }
      }
      throw new IOException("通信が完了前に途切れました。途中のデータは完成モデルとして反映していません。再実行できます。");
    }finally{c.disconnect();cancel.connection=null;}
  }
  static String outputText(JSONObject response){
    if(response==null)return "";StringBuilder b=new StringBuilder();JSONArray output=response.optJSONArray("output");if(output==null)return "";
    for(int i=0;i<output.length();i++){JSONObject o=output.optJSONObject(i);if(o==null)continue;JSONArray content=o.optJSONArray("content");if(content==null)continue;for(int j=0;j<content.length();j++){JSONObject item=content.optJSONObject(j);if(item==null)continue;if(item.optString("type").equals("output_text"))b.append(item.optString("text"));else if(item.optString("type").equals("refusal"))b.append(item.optString("refusal"));}}
    return b.toString();
  }
}
