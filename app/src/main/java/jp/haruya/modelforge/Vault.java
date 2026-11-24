package jp.haruya.modelforge;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.security.KeyStore;
import java.nio.charset.StandardCharsets;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class Vault {
  private final AtomicFile file;
  private final SecretKey key;
  Vault(Context context)throws Exception{
    file=new AtomicFile(new File(context.getNoBackupFilesDir(),"chatgpt-vault.bin"));
    KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
    String alias="modelforge.oauth.v1";
    if(!ks.containsAlias(alias)){KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();}
    key=(SecretKey)ks.getKey(alias,null);
  }
  synchronized JSONObject read()throws Exception{
    if(!file.getBaseFile().exists())return new JSONObject();
    byte[] bytes=file.readFully();if(bytes.length<30)throw new IOException("保存された認証情報が壊れています");
    int len=bytes[0]&255;if(len!=12)throw new IOException("認証情報の形式が不正です");
    Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,bytes,1,len));
    return new JSONObject(new String(c.doFinal(bytes,1+len,bytes.length-1-len),StandardCharsets.UTF_8));
  }
  synchronized void write(JSONObject data)throws Exception{
    Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);byte[] iv=c.getIV(),cipher=c.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));FileOutputStream out=null;
    try{out=file.startWrite();out.write(iv.length);out.write(iv);out.write(cipher);file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
  }
}
