package com.dhaval.trucktracker;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.*;
import com.google.android.gms.location.*;
import org.json.JSONObject;
import java.util.Locale;

public class TrackingService extends Service {
  public static final String CH="truck_tracking";
  private static final long SAMPLE_MS=5000L;
  private static final long MOVING_UPLOAD_MS=5000L;
  private static final long STOPPED_UPLOAD_MS=30000L;
  private static final float GOOD_ACCURACY_M=80f;
  private static final float FALLBACK_ACCURACY_M=150f;
  private static final float STOP_RADIUS_M=12f;
  private static final float MOVING_SPEED_KMH=4.0f;

  private FusedLocationProviderClient fused;
  private LocationCallback callback;
  private Prefs prefs;
  private SupabaseApi api;
  private String truckId,truckNo,token;
  private long lastUploadAt=0;
  private Location lastAccepted=null;
  private int stationarySamples=0;

  @Override public void onCreate(){super.onCreate();prefs=new Prefs(this);api=new SupabaseApi(this);createChannel();fused=LocationServices.getFusedLocationProviderClient(this);}

  private void createChannel(){
    if(Build.VERSION.SDK_INT>=26){
      NotificationChannel c=new NotificationChannel(CH,"Truck GPS Tracking",NotificationManager.IMPORTANCE_LOW);
      c.setDescription("Visible while fleet GPS tracking is active");
      c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
      getSystemService(NotificationManager.class).createNotificationChannel(c);
    }
  }

  private Notification note(String text){
    Intent open=new Intent(this,MainActivity.class);
    PendingIntent pi=PendingIntent.getActivity(this,10,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this,CH).setContentTitle("Truck Tracking Active • "+(truckNo==null?"Truck":truckNo)).setContentText(text)
      .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true).build();
  }

  private void setState(String state,String error){prefs.put("gps_state",state==null?"":state);prefs.put("last_error",error==null?"":error);}
  private void updateNotification(String text){try{((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1001,note(text));}catch(Exception ignored){}}

  @Override public int onStartCommand(Intent i,int flags,int startId){
    truckId=i!=null&&i.getStringExtra("truck_id")!=null?i.getStringExtra("truck_id"):prefs.get("truck_id","");
    truckNo=i!=null&&i.getStringExtra("truck_no")!=null?i.getStringExtra("truck_no"):prefs.get("truck_no","Truck");
    token=prefs.get("device_token","");
    if(truckId.isEmpty()||token.isEmpty()||!prefs.getBool("desired_tracking",false)){stopSelf();return START_NOT_STICKY;}
    startForeground(1001,note("High-accuracy GPS starting…"));prefs.putBool("tracking",true);setState("Starting high-accuracy GPS…","");startLocation();return START_STICKY;
  }

  private void startLocation(){
    if(checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
      prefs.putBool("tracking",false);setState("Location permission missing","Allow precise Location for Truck Tracker Driver");updateNotification("Location permission missing");stopSelf();return;
    }
    LocationRequest req=new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,SAMPLE_MS)
      .setMinUpdateIntervalMillis(3000L).setMaxUpdateDelayMillis(8000L).setMinUpdateDistanceMeters(0f).build();
    callback=new LocationCallback(){@Override public void onLocationResult(LocationResult result){if(result==null)return;for(Location l:result.getLocations())handleLocation(l);}};
    try{
      fused.requestLocationUpdates(req,callback,Looper.getMainLooper());
      fused.getLastLocation().addOnSuccessListener(l->{if(l!=null && System.currentTimeMillis()-l.getTime()<120000L)handleLocation(l);});
    }catch(Exception e){setState("GPS start failed",e.getMessage());updateNotification("GPS start failed • open app to check setup");}
  }

  private synchronized void handleLocation(Location l){
    if(l==null||!prefs.getBool("desired_tracking",false))return;
    long age=Math.abs(System.currentTimeMillis()-l.getTime());
    if(age>120000L)return;
    float acc=l.hasAccuracy()?l.getAccuracy():9999f;
    if(acc>FALLBACK_ACCURACY_M){setState("Waiting for better GPS ("+Math.round(acc)+" m)","");updateNotification("GPS weak • accuracy "+Math.round(acc)+" m");return;}

    float moved=0f;
    if(lastAccepted!=null)moved=lastAccepted.distanceTo(l);
    double rawKmh=l.hasSpeed()?Math.max(0,l.getSpeed()*3.6):0;
    boolean gpsSaysMoving=rawKmh>=MOVING_SPEED_KMH;
    boolean positionSaysMoving=lastAccepted!=null && moved>Math.max(STOP_RADIUS_M,Math.min(35f,(acc+(lastAccepted.hasAccuracy()?lastAccepted.getAccuracy():acc))*0.35f));
    boolean moving=gpsSaysMoving||positionSaysMoving;
    if(moving)stationarySamples=0;else stationarySamples++;

    // Kill the common 1–2 km/h GPS drift while parked. Require real speed or meaningful displacement.
    double kmh=moving?rawKmh:0.0;
    if(!gpsSaysMoving && positionSaysMoving && lastAccepted!=null){
      long dt=Math.max(1000L,l.getTime()-lastAccepted.getTime());
      double derived=moved/(dt/1000.0)*3.6;
      if(derived>=MOVING_SPEED_KMH && derived<160)kmh=derived;
    }

    long now=System.currentTimeMillis();
    long throttle=moving?MOVING_UPLOAD_MS:STOPPED_UPLOAD_MS;
    // A very good fix can replace a poorer previous point immediately when movement is real.
    if(lastUploadAt>0 && now-lastUploadAt<throttle){lastAccepted=l;return;}
    if(acc>GOOD_ACCURACY_M && lastAccepted!=null && !moving && stationarySamples<3){lastAccepted=l;return;}

    double total=prefs.getFloat("distance",0f);
    String la=prefs.get("last_lat",""),lo=prefs.get("last_lon","");
    if(!la.isEmpty()&&!lo.isEmpty()){
      try{float[] res=new float[1];Location.distanceBetween(Double.parseDouble(la),Double.parseDouble(lo),l.getLatitude(),l.getLongitude(),res);double meters=res[0];
        // Distance only counts when movement clears the accuracy/drift floor.
        double floor=Math.max(15.0,Math.min(45.0,acc*0.55));if(moving&&meters>=floor&&meters<3000)total+=meters/1000.0;
      }catch(Exception ignored){}
    }
    prefs.put("last_lat",String.valueOf(l.getLatitude()));prefs.put("last_lon",String.valueOf(l.getLongitude()));prefs.putFloat("distance",(float)total);
    prefs.putLong("last_fix_at",now);lastAccepted=l;lastUploadAt=now;
    final double finalTotal=total,finalKmh=kmh;final float finalAcc=acc;final double lat=l.getLatitude(),lon=l.getLongitude();
    setState(moving?"Sending live GPS…":"Stopped • GPS held accurately","");

    new Thread(()->{
      try{
        JSONObject r=api.sendLocation(truckId,token,lat,lon,finalKmh,finalAcc,finalTotal);
        if(!r.optBoolean("trip_active",true)){prefs.putBool("desired_tracking",false);prefs.putBool("tracking",false);prefs.remove("trip_id");setState("Trip closed by office","");stopSelf();return;}
        prefs.putLong("last_sent_at",System.currentTimeMillis());prefs.put("last_sent_lat",String.valueOf(lat));prefs.put("last_sent_lon",String.valueOf(lon));
        setState(String.format(Locale.US,"GPS SENT • %.0f m • %s",finalAcc,finalKmh>=MOVING_SPEED_KMH?"MOVING":"STOPPED"),"");
        updateNotification(String.format(Locale.US,"GPS sent • %.1f km • %.0f m accuracy",prefs.getFloat("distance",0f),finalAcc));
      }catch(Exception e){String msg=e.getMessage()==null?"Network/API error":e.getMessage();if(msg.length()>120)msg=msg.substring(0,120);setState("GPS saved • upload retrying",msg);updateNotification("No internet • GPS service stays active and will retry");lastUploadAt=0;}
    }).start();
  }

  @Override public void onTaskRemoved(Intent rootIntent){
    // Swiping the app away must not end an active trip. Ask Android to recreate this sticky foreground service.
    if(prefs.getBool("desired_tracking",false)){
      try{Intent s=new Intent(getApplicationContext(),TrackingService.class).putExtra("truck_id",prefs.get("truck_id","")).putExtra("truck_no",prefs.get("truck_no","Truck"));
        PendingIntent pi=PendingIntent.getService(getApplicationContext(),991,s,PendingIntent.FLAG_ONE_SHOT|PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am=(AlarmManager)getSystemService(ALARM_SERVICE);if(am!=null)am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+3000L,pi);
      }catch(Exception ignored){}
    }
    super.onTaskRemoved(rootIntent);
  }

  @Override public void onDestroy(){if(fused!=null&&callback!=null)try{fused.removeLocationUpdates(callback);}catch(Exception ignored){}prefs.putBool("tracking",false);super.onDestroy();}
  @Override public android.os.IBinder onBind(Intent intent){return null;}
}
