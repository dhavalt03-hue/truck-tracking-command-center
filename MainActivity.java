package com.dhaval.trucktracker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class MainActivity extends Activity {
  private Button startBtn,setupBtn;
  private TextView status,truckText,helper,health;
  private Prefs prefs;
  private SupabaseApi api;
  private boolean startAfterPermission=false;
  private final Handler uiHandler=new Handler(Looper.getMainLooper());
  private final Runnable uiTicker=new Runnable(){@Override public void run(){refreshUi();uiHandler.postDelayed(this,3000);}};

  @Override public void onCreate(Bundle b){
    super.onCreate(b);
    setContentView(R.layout.activity_main);
    prefs=new Prefs(this);api=new SupabaseApi(this);
    startBtn=findViewById(R.id.startBtn);setupBtn=findViewById(R.id.setupBtn);
    status=findViewById(R.id.statusText);truckText=findViewById(R.id.truckText);helper=findViewById(R.id.helperText);health=findViewById(R.id.healthText);
    startBtn.setOnClickListener(v->startTracking());
    setupBtn.setOnClickListener(v->openPhoneSetup());
    handleIntent(getIntent());refreshUi();
  }

  @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleIntent(intent);refreshUi();}

  private void handleIntent(Intent intent){
    Uri d=intent==null?null:intent.getData();
    if(d==null||!"trucktracker".equalsIgnoreCase(d.getScheme()))return;
    String truck=d.getQueryParameter("truck"),token=d.getQueryParameter("token"),no=d.getQueryParameter("no");
    if(truck!=null&&!truck.isEmpty()&&token!=null&&!token.isEmpty()){
      prefs.put("truck_id",truck);prefs.put("device_token",token);prefs.put("truck_no",no==null||no.isEmpty()?"Assigned Truck":no);
      prefs.putBool("tracking",false);prefs.putBool("desired_tracking",false);prefs.put("gps_state","Trip assigned • ready to start");prefs.put("last_error","");
      Toast.makeText(this,"Trip received from office",Toast.LENGTH_SHORT).show();
    }
  }

  private String backgroundSetupSummary(){
    boolean fine=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    boolean bg=Build.VERSION.SDK_INT<29||checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED;
    PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
    boolean unrestricted=Build.VERSION.SDK_INT<23||pm.isIgnoringBatteryOptimizations(getPackageName());
    return "Phone setup: Location "+(fine?"✓":"✗")+"  • Background "+(bg?"✓":"!")+"  • Battery "+(unrestricted?"✓":"!");
  }

  private void refreshUi(){
    boolean assigned=!prefs.get("truck_id","").isEmpty()&&!prefs.get("device_token","").isEmpty();
    boolean on=prefs.getBool("tracking",false)||prefs.getBool("desired_tracking",false);
    if(!assigned){
      truckText.setText("NO TRIP ASSIGNED");helper.setText("Open the trip message sent by your office on WhatsApp. No login, password or truck selection is required.");
      startBtn.setVisibility(View.GONE);status.setText("Waiting for office trip link");status.setBackgroundColor(0xFF13233A);health.setText(backgroundSetupSummary());return;
    }
    truckText.setText(prefs.get("truck_no","Assigned Truck"));startBtn.setVisibility(View.VISIBLE);
    String gps=prefs.get("gps_state",on?"Waiting for GPS…":"Trip ready");String err=prefs.get("last_error","");long sent=prefs.getLong("last_sent_at",0);
    String sentText=sent>0?DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(sent)):"Not sent yet";
    health.setText(backgroundSetupSummary()+"\nGPS: "+gps+"\nLast GPS sent: "+sentText+(err.isEmpty()?"":"\nLast error: "+err));
    if(on){
      status.setText("● TRACKING ACTIVE\nPhone can be locked");status.setBackgroundColor(0xFF103D2B);startBtn.setEnabled(false);startBtn.setText("✓  TRIP STARTED");
      helper.setText("Foreground GPS tracking is running. Keep Location and mobile data ON. If updates stop, tap CHECK PHONE SETUP.");
    }else{
      status.setText("Trip ready");status.setBackgroundColor(0xFF13233A);startBtn.setEnabled(true);startBtn.setText("▶  START TRIP");
      helper.setText("Tap START TRIP. Allow Location and notifications. For best long-trip reliability, also set Battery to Unrestricted and allow background location when your phone offers it.");
    }
  }

  private boolean hasCorePermissions(){
    return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED &&
      (Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED);
  }

  private boolean requestNeededPermissions(){
    List<String> req=new ArrayList<>();
    if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)req.add(Manifest.permission.ACCESS_FINE_LOCATION);
    if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)req.add(Manifest.permission.POST_NOTIFICATIONS);
    if(!req.isEmpty()){requestPermissions(req.toArray(new String[0]),22);return true;}return false;
  }

  @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
    super.onRequestPermissionsResult(requestCode,permissions,grantResults);
    if(requestCode==22){
      if(hasCorePermissions()&&startAfterPermission){startAfterPermission=false;new Handler(Looper.getMainLooper()).postDelayed(this::startTracking,300);}
      else if(!hasCorePermissions())Toast.makeText(this,"Location permission is required to track the trip",Toast.LENGTH_LONG).show();
    }
  }

  private void startTracking(){
    String id=prefs.get("truck_id",""),token=prefs.get("device_token","");
    if(id.isEmpty()||token.isEmpty()){Toast.makeText(this,"Open the WhatsApp trip link from your office first",Toast.LENGTH_LONG).show();return;}
    if(requestNeededPermissions()){startAfterPermission=true;return;}
    startBtn.setEnabled(false);status.setText("Starting trip…");
    new Thread(()->{
      try{
        org.json.JSONObject r=api.startTracking(id,token);
        if(!r.optBoolean("ok"))throw new Exception(r.optString("message","Trip is not active in owner dashboard"));
        prefs.putFloat("distance",0);prefs.put("last_lat","");prefs.put("last_lon","");prefs.put("trip_id",r.optString("trip_id",""));prefs.putBool("desired_tracking",true);prefs.put("gps_state","Starting GPS…");prefs.put("last_error","");
        Intent s=new Intent(this,TrackingService.class).putExtra("truck_id",id).putExtra("truck_no",prefs.get("truck_no","Truck"));
        if(Build.VERSION.SDK_INT>=26)startForegroundService(s);else startService(s);
        prefs.putBool("tracking",true);
        runOnUiThread(()->{refreshUi();Toast.makeText(this,"Trip started. Background GPS is ON.",Toast.LENGTH_LONG).show();});
      }catch(Exception e){runOnUiThread(()->{prefs.put("last_error",e.getMessage()==null?"Could not start":e.getMessage());prefs.putBool("desired_tracking",false);prefs.putBool("tracking",false);refreshUi();Toast.makeText(this,"Could not start: "+e.getMessage(),Toast.LENGTH_LONG).show();});}
    }).start();
  }

  private void openPhoneSetup(){
    // First try to ask Android to remove battery optimization for this tracking app.
    try{
      if(Build.VERSION.SDK_INT>=23){
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        if(!pm.isIgnoringBatteryOptimizations(getPackageName())){
          Intent i=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName()));startActivity(i);
          Toast.makeText(this,"Choose Allow, then return and tap CHECK PHONE SETUP again.",Toast.LENGTH_LONG).show();return;
        }
      }
    }catch(Exception ignored){}
    // App settings is where Android/OEMs expose 'Allow all the time', background data and battery controls.
    Intent i=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()));startActivity(i);
    Toast.makeText(this,"Set Location to the strongest available option and Battery to Unrestricted / Don't optimize.",Toast.LENGTH_LONG).show();
  }

  private void ensureServiceIfWanted(){
    if(!prefs.getBool("desired_tracking",false)||!hasCorePermissions())return;
    String id=prefs.get("truck_id",""),token=prefs.get("device_token","");if(id.isEmpty()||token.isEmpty())return;
    try{Intent s=new Intent(this,TrackingService.class).putExtra("truck_id",id).putExtra("truck_no",prefs.get("truck_no","Truck"));if(Build.VERSION.SDK_INT>=26)startForegroundService(s);else startService(s);}catch(Exception ignored){}
  }

  @Override protected void onResume(){super.onResume();refreshUi();ensureServiceIfWanted();uiHandler.removeCallbacks(uiTicker);uiHandler.postDelayed(uiTicker,1000);}
  @Override protected void onPause(){uiHandler.removeCallbacks(uiTicker);super.onPause();}
}
