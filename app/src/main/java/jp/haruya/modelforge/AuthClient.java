package jp.haruya.modelforge;

import android.content.*;
import android.net.Uri;
import android.util.Base64;
import org.json.*;
import org.jose4j.jwk.*;
import org.jose4j.jws.*;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwt.*;
import org.jose4j.jwt.consumer.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

final class AuthClient {
  static final String AUTH="https://auth.openai.com/api/accounts/authorize",TOKEN="https://auth.openai.com/api/accounts/oauth/token",RESOURCE="https://api.openai.com/v1",ISSUER="https://auth.openai.com";
  private final Vault vault;
  private JSONObject db;
  volatile boolean signingIn=false;
  volatile String message="";
  private volatile ServerSocket listener;
  private volatile boolean cancelled;
  private String jwks;private long jwksSaved;
  interface Browser {void open(String url);}
  AuthClient(Context context)throws Exception{
    vault=new Vault(context);db=vault.read();if(!db.has("host"))db.put("host","urn:uuid:"+UUID.randomUUID());if(!db.has("profiles"))db.put("profiles",new JSONArray());vault.write(db);
  }
  synchronized JSONObject state()throws Exception{
    JSONArray safe=new JSONArray(),a=db.getJSONArray("profiles");for(int i=0;i<a.length();i++){JSONObject p=a.getJSONObject(i);safe.put(new JSONObject().put("id",p.getString("id")).put("label",p.optString("label","アカウント "+(i+1))).put("email",p.optString("email")).put("connected",!p.optString("access_token").isEmpty()).put("sharing",sharing(p)));}
    return new JSONObject().put("active",db.optString("active")).put("profiles",safe).put("signingIn",signingIn).put("message",message);
  }
  private boolean sharing(JSONObject p){return !p.optString("access_token").isEmpty()&&Arrays.asList(p.optString("scope").split("\\s+")).contains("chatgpt.tokens.use.direct");}
  private synchronized JSONObject profile(String id)throws Exception{JSONArray a=db.getJSONArray("profiles");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals(id))return a.getJSONObject(i);return null;}
  synchronized JSONObject select(String id)throws Exception{if(signingIn)throw new IOException("ログイン処理中です");if(profile(id)==null)throw new IOException("アカウントが見つかりません");db.put("active",id);vault.write(db);return state();}
  private static String random(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.encodeToString(b,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
  synchronized String activeId(){return db.optString("active");}
  void cancelLogin(){cancelled=true;ServerSocket s=listener;if(s!=null)try{s.close();}catch(Exception ignored){} }
  void login(boolean newAccount,Browser browser)throws Exception{
    synchronized(this){if(signingIn)throw new IOException("ログイン処理中です");signingIn=true;cancelled=false;message="ブラウザーでChatGPTにログインしてください。完了後、このアプリへ戻ってください。";}
    try{
      JSONObject p;String host;
      synchronized(this){p=newAccount?null:profile(db.optString("active"));if(p==null)p=new JSONObject().put("id",UUID.randomUUID().toString()).put("label","アカウント "+(db.getJSONArray("profiles").length()+1));else p=new JSONObject(p.toString());host=db.getString("host");}
      final String state=random(),nonce=random(),verifier=random();String challenge=Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
      ServerSocket server=new ServerSocket();server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0));server.setSoTimeout(1000);listener=server;
      String redirect="http://127.0.0.1:"+server.getLocalPort()+"/auth/callback";String savedClient=p.optString("client_id"),client=savedClient.isEmpty()?"dynamic_agent_client":savedClient;
      Map<String,String> params=Net.pairs("client_id",client,"ext_agent_host_id",host,"response_type","code","redirect_uri",redirect,"scope","openid profile email offline_access resource.invoke chatgpt.tokens.use.direct","resource",RESOURCE,"state",state,"nonce",nonce,"code_challenge_method","S256","code_challenge",challenge);
      if(savedClient.isEmpty())params.put("agent_name_hint","ModelForge 3D");else{params.put("id_token_hint",p.optString("id_token"));params.put("login_hint",p.optString("email"));}
      browser.open(AUTH+"?"+Net.form(params));
      Uri callback=null;long deadline=System.currentTimeMillis()+15*60*1000;
      while(!cancelled&&System.currentTimeMillis()<deadline){
        try(Socket socket=server.accept()){
          socket.setSoTimeout(4000);BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));String line=reader.readLine();if(line==null||line.length()>32768){reply(socket,400,"無効なリクエストです");continue;}
          String[] first=line.split(" ");if(first.length<3||!first[0].equals("GET")){reply(socket,400,"無効なリクエストです");continue;}
          Uri uri=Uri.parse(first[1]);if(!"/auth/callback".equals(uri.getPath())){reply(socket,404,"ページがありません");continue;}
          if(!MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),Objects.toString(uri.getQueryParameter("state"),"").getBytes(StandardCharsets.UTF_8))){reply(socket,400,"ログイン状態が一致しません。アプリからやり直してください。");continue;}
          callback=uri;reply(socket,200,"認証結果を受け取りました。ModelForge 3Dに戻って接続結果を確認してください。");break;
        }catch(SocketTimeoutException ignored){}
      }
      server.close();listener=null;if(cancelled)throw new IOException("ログインを中止しました");if(callback==null)throw new IOException("ログインの待機時間を過ぎました。もう一度接続してください。");
      if(callback.getQueryParameter("error")!=null)throw new IOException("ChatGPTへの接続が許可されませんでした。利用許可を確認して再接続してください。");
      String returned=callback.getQueryParameter("client_id");
      if(savedClient.isEmpty()){if(returned==null||returned.isEmpty()||returned.equals("dynamic_agent_client"))throw new IOException("登録されたクライアントIDが返りませんでした");client=returned;}
      else if(returned!=null&&!returned.equals(savedClient))throw new IOException("登録済みアカウントとクライアントIDが一致しません");
      String code=callback.getQueryParameter("code");if(code==null||code.isEmpty())throw new IOException("認証コードが返りませんでした");
      p.put("client_id",client);saveRegistration(p,false);message="ChatGPTの接続情報を確認しています…";
      JSONObject tokens=Net.postForm(TOKEN,Net.pairs("grant_type","authorization_code","client_id",client,"code",code,"code_verifier",verifier,"redirect_uri",redirect,"resource",RESOURCE));
      JwtClaims identity=verify(tokens.getString("id_token"),client,nonce);
      if(!p.optString("subject").isEmpty()&&!p.getString("subject").equals(identity.getSubject()))throw new IOException("選択したアカウントと認証されたアカウントが一致しません");
      p.put("subject",identity.getSubject());p.put("issuer",identity.getIssuer());p.put("email",identity.getStringClaimValue("email"));storeTokens(p,tokens,false);
      if(cancelled)throw new IOException("ログインを中止しました");saveRegistration(p,true);
      message=sharing(p)?"ChatGPTに接続しました。プランの利用枠で生成できます。":"ログイン済みですが、ChatGPTプランの利用が許可されていません。再接続して利用を許可してください。";
    }catch(Exception e){message=e.getMessage()==null?"ログインできませんでした":e.getMessage();throw e;}finally{ServerSocket s=listener;if(s!=null)try{s.close();}catch(Exception ignored){}listener=null;signingIn=false;}
  }
  private static void reply(Socket s,int status,String message)throws IOException{
    String html="<!doctype html><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>ModelForge 3D</title><body style='background:#142036;color:#e8f6ff;font:16px system-ui;padding:36px;line-height:1.8'><h2>ModelForge 3D</h2><p>"+message+"</p>";
    byte[] body=html.getBytes(StandardCharsets.UTF_8);OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 "+status+" OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
  }
  private synchronized void saveRegistration(JSONObject p,boolean activate)throws Exception{
    JSONArray a=db.getJSONArray("profiles");boolean found=false;for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals(p.getString("id"))){a.put(i,p);found=true;break;}if(!found)a.put(p);if(activate||db.optString("active").isEmpty())db.put("active",p.getString("id"));vault.write(db);
  }
  private synchronized JwtClaims verify(String token,String client,String nonce)throws Exception{
    JsonWebSignature jws=new JsonWebSignature();jws.setCompactSerialization(token);String alg=jws.getAlgorithmHeaderValue();
    if(!AlgorithmIdentifiers.RSA_USING_SHA256.equals(alg)&&!AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256.equals(alg))throw new IOException("IDトークンの署名方式が許可されていません");
    JsonWebKey key=null;
    for(int pass=0;pass<2&&key==null;pass++){
      if(jwks==null||System.currentTimeMillis()-jwksSaved>3600000||pass>0){jwks=Net.request(ISSUER+"/.well-known/jwks.json","GET",null,null,null);jwksSaved=System.currentTimeMillis();}
      for(JsonWebKey k:new JsonWebKeySet(jwks).getJsonWebKeys())if(Objects.equals(k.getKeyId(),jws.getKeyIdHeaderValue())&&(k.getUse()==null||k.getUse().equals("sig"))&&(k.getAlgorithm()==null||k.getAlgorithm().equals(alg))){key=k;break;}
    }
    if(key==null)throw new IOException("OpenAIの公開署名鍵が見つかりません");
    JwtConsumer consumer=new JwtConsumerBuilder().setRequireExpirationTime().setRequireSubject().setAllowedClockSkewInSeconds(30).setExpectedIssuer(ISSUER).setExpectedAudience(client).setVerificationKey(key.getKey()).setJwsAlgorithmConstraints(new AlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT,AlgorithmIdentifiers.RSA_USING_SHA256,AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256)).build();
    JwtClaims claims=consumer.processToClaims(token);
    if(nonce!=null&&!MessageDigest.isEqual(nonce.getBytes(StandardCharsets.UTF_8),Objects.toString(claims.getStringClaimValue("nonce"),"").getBytes(StandardCharsets.UTF_8)))throw new IOException("IDトークンの nonce が一致しません");
    return claims;
  }
  private void storeTokens(JSONObject p,JSONObject tokens,boolean refresh)throws Exception{
    String access=tokens.getString("access_token");if(access.isEmpty())throw new IOException("アクセストークンが空です");p.put("access_token",access);p.put("expires_at",System.currentTimeMillis()+tokens.optLong("expires_in",3600)*1000);
    if(tokens.has("refresh_token"))p.put("refresh_token",tokens.getString("refresh_token"));
    if(tokens.has("id_token"))p.put("id_token",tokens.getString("id_token"));
    if(tokens.has("scope"))p.put("scope",tokens.getString("scope"));else if(!refresh)p.put("scope","");
  }
  synchronized String token(boolean force)throws Exception{
    JSONObject p=profile(db.optString("active"));if(p==null||!sharing(p))throw new IOException("ChatGPTに接続し、プランの利用を許可してください");
    if(force||System.currentTimeMillis()>p.optLong("expires_at")-90000){
      if(p.optString("refresh_token").isEmpty())throw new IOException("同じアカウントで再ログインしてください");
      JSONObject tokens=Net.postForm(TOKEN,Net.pairs("grant_type","refresh_token","client_id",p.getString("client_id"),"refresh_token",p.getString("refresh_token"),"resource",RESOURCE));
      if(tokens.has("id_token")){JwtClaims identity=verify(tokens.getString("id_token"),p.getString("client_id"),null);if(!identity.getSubject().equals(p.getString("subject")))throw new IOException("更新時のアカウントが一致しません");}
      storeTokens(p,tokens,true);vault.write(db);if(!sharing(p))throw new IOException("ChatGPTプランの利用許可がありません");
    }return p.getString("access_token");
  }
  JSONArray models()throws Exception{
    JSONObject result;try{result=Net.get(RESOURCE+"/models",token(false));}catch(Net.HttpError e){if(e.status!=401)throw e;result=Net.get(RESOURCE+"/models",token(true));}
    JSONArray a=result.optJSONArray("models"),safe=new JSONArray();if(a==null)throw new IOException("モデル一覧の形式を確認できませんでした。再接続してください。");
    for(int i=0;i<a.length();i++){JSONObject m=a.getJSONObject(i);if(!m.optString("visibility").equals("list"))continue;String slug=m.optString("slug");if(slug.isEmpty())continue;safe.put(new JSONObject().put("slug",slug).put("display_name",m.optString("display_name",slug)));}return safe;
  }
  synchronized String logout()throws Exception{
    if(signingIn)throw new IOException("ログイン処理を中止してからログアウトしてください");JSONObject p=profile(db.optString("active"));if(p==null)return "";boolean revoked=false;
    if(!p.optString("refresh_token").isEmpty()){
      try{JSONObject config=Net.get(ISSUER+"/.well-known/openid-configuration",null);String endpoint=config.getString("revocation_endpoint");URI uri=new URI(endpoint);if(!"https".equals(uri.getScheme())||!"auth.openai.com".equals(uri.getHost()))throw new IOException("認証エンドポイントが一致しません");Net.request(endpoint,"POST",null,"application/x-www-form-urlencoded",Net.form(Net.pairs("token",p.getString("refresh_token"),"token_type_hint","refresh_token","client_id",p.getString("client_id"))));revoked=true;}catch(Exception ignored){}
    }else revoked=true;
    for(String k:new String[]{"access_token","refresh_token","id_token","expires_at","scope"})p.remove(k);vault.write(db);
    message=revoked?"ログアウトしました":"端末からログアウトしました。サーバー側の失効は確認できていません。ChatGPTの設定からこのアプリを切断できます。";return message;
  }
}
