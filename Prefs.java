package com.dhaval.trucktracker;
import android.content.Context;import android.content.SharedPreferences;
public class Prefs {
  private final SharedPreferences p;
  public Prefs(Context c){p=c.getSharedPreferences("truck_tracker",Context.MODE_PRIVATE);}
  public void put(String k,String v){p.edit().putString(k,v).apply();}
  public String get(String k,String d){return p.getString(k,d);}
  public void putBool(String k,boolean v){p.edit().putBoolean(k,v).apply();}
  public boolean getBool(String k,boolean d){return p.getBoolean(k,d);}
  public void putFloat(String k,float v){p.edit().putFloat(k,v).apply();}
  public float getFloat(String k,float d){return p.getFloat(k,d);}
  public void putLong(String k,long v){p.edit().putLong(k,v).apply();}
  public long getLong(String k,long d){return p.getLong(k,d);}
  public void remove(String k){p.edit().remove(k).apply();}
  public void clearAssignment(){p.edit().remove("truck_id").remove("truck_no").remove("device_token").remove("pairing_code").remove("trip_id").remove("gps_state").remove("last_error").remove("last_sent_at").putBoolean("tracking",false).putBoolean("desired_tracking",false).apply();}
}
