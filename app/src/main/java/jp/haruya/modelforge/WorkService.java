package jp.haruya.modelforge;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

public final class WorkService extends Service {
  private PowerManager.WakeLock wake;
  private final Handler handler=new Handler(Looper.getMainLooper());
  @Override public void onCreate(){super.onCreate();NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("generation","3D生成・ChatGPT接続",NotificationManager.IMPORTANCE_LOW));}
  @Override public int onStartCommand(Intent intent,int flags,int startId){
    AppCore core=AppCore.get(this);String action=intent==null?"":intent.getAction();
    if("stop".equals(action)){core.cancelJob();if(core.auth!=null)core.auth.cancelLogin();stopSelf();return START_NOT_STICKY;}
    Intent open=new Intent(this,MainActivity.class);PendingIntent content=PendingIntent.getActivity(this,1,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop=PendingIntent.getService(this,2,new Intent(this,WorkService.class).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Notification note=new Notification.Builder(this,"generation").setSmallIcon(R.drawable.ic_stat).setContentTitle("ModelForge 3D").setContentText("generate".equals(action)?"3D設計を生成しています":"ChatGPTへの接続を待っています").setContentIntent(content).setOngoing(true).addAction(new Notification.Action.Builder(null,"停止",stop).build()).build();
    if(Build.VERSION.SDK_INT>=29)startForeground(11,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(11,note);
    if(wake==null){wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ModelForge:Generation");wake.acquire(25*60*1000L);}
    if("generate".equals(action))core.io.execute(()->{core.generate();handler.post(()->{if(core.auth==null||!core.auth.signingIn)stopSelf();});});
    if("auth".equals(action)){handler.postDelayed(new Runnable(){public void run(){if(core.auth!=null&&core.auth.signingIn)handler.postDelayed(this,1500);else if(!core.working)stopSelf();}},2000);}
    return START_NOT_STICKY;
  }
  @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
  @Override public void onTimeout(int startId,int fgsType){AppCore.get(this).cancelJob();if(AppCore.get(this).auth!=null)AppCore.get(this).auth.cancelLogin();stopSelf();}
  @Override public IBinder onBind(Intent intent){return null;}
}
