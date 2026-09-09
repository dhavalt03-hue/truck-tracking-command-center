package com.dhaval.trucktracker;
import android.content.*;import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context context, Intent intent){
    Prefs prefs=new Prefs(context);
    if(!prefs.getBool("desired_tracking",false))return;
    if(prefs.get("truck_id","").isEmpty()||prefs.get("device_token","").isEmpty())return;
    Intent s=new Intent(context,TrackingService.class)
      .putExtra("truck_id",prefs.get("truck_id",""))
      .putExtra("truck_no",prefs.get("truck_no","Truck"));
    try{if(Build.VERSION.SDK_INT>=26)context.startForegroundService(s);else context.startService(s);}catch(Exception ignored){
      // On heavily restricted Android builds, opening the app once after reboot will resume tracking.
    }
  }
}
