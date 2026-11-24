package jp.haruya.modelforge;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.*;
import java.util.concurrent.atomic.AtomicReference;

public final class NetTest {
  static int count;
  static void check(boolean b,String message){if(!b)throw new AssertionError(message);count++;}
  static String evt(String type,JSONObject extra)throws Exception{extra.put("type",type);return "data: "+extra+"\n\n";}
  public static void main(String[] args)throws Exception{
    AtomicReference<String> payload=new AtomicReference<>("");AtomicReference<String> received=new AtomicReference<>("");HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/responses",exchange->{received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] data=payload.get().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);exchange.close();});server.start();String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/responses";
    try{
      JSONObject request=new JSONObject().put("stream",true).put("store",false);
      payload.set(evt("response.output_text.delta",new JSONObject().put("delta","hello"))+evt("response.completed",new JSONObject().put("response",new JSONObject())));
      check(Net.streamAt(endpoint,"test-only",request,new Net.Cancel(),c->{}).equals("hello"),"completed output");check(!new JSONObject(received.get()).getBoolean("store"),"store remains false");
      payload.set(evt("response.output_text.delta",new JSONObject().put("delta","partial")));
      try{Net.streamAt(endpoint,"test-only",request,new Net.Cancel(),c->{});throw new AssertionError("truncated stream accepted");}catch(IOException expected){check(expected.getMessage().contains("途切れ"),"truncation reported");}
      payload.set(evt("response.output_text.delta",new JSONObject().put("delta","partial"))+evt("response.failed",new JSONObject().put("response",new JSONObject().put("error",new JSONObject().put("code","subscription_sharing_usage_limit_exceeded")))));
      try{Net.streamAt(endpoint,"test-only",request,new Net.Cancel(),c->{});throw new AssertionError("usage failure accepted");}catch(Net.HttpError e){check(e.getMessage().contains("利用上限"),"late usage-limit event");}
      payload.set(evt("response.incomplete",new JSONObject()));try{Net.streamAt(endpoint,"test-only",request,new Net.Cancel(),c->{});throw new AssertionError("incomplete accepted");}catch(IOException e){check(e.getMessage().contains("完了前"),"incomplete reported");}
      Net.Cancel cancel=new Net.Cancel();cancel.cancel();try{Net.streamAt(endpoint,"test-only",request,cancel,c->{});throw new AssertionError("cancel ignored");}catch(IOException e){check(e.getMessage().contains("停止"),"cancel respected");}
      check(Net.error(401,"{}").getMessage().contains("再ログイン"),"expired session guidance");
      check(Net.outputText(new JSONObject("{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}")).equals("ok"),"completed text extraction");
      System.out.println("Native transport checks passed: "+count);
    }finally{server.stop(0);}
  }
}
