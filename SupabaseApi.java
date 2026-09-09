package com.dhaval.trucktracker;
import android.content.Context;import org.json.*;import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;
public class SupabaseApi {
  public static final String BASE="https://kdsbenbjvxzubpxuipvk.supabase.co";
  public static final String KEY="sb_publishable_6oPcc0apzZyXZfUgPp-gzA_aZuCqrmJ";
  private final Prefs prefs; public SupabaseApi(Context c){prefs=new Prefs(c);}
  private String read(InputStream is)throws Exception{if(is==null)return"";BufferedReader r=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();String s;while((s=r.readLine())!=null)b.append(s);return b.toString();}
  private String call(String method,String path,String body)throws Exception{
    HttpURLConnection c=(HttpURLConnection)new URL(BASE+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(20000);c.setReadTimeout(20000);c.setRequestProperty("apikey",KEY);c.setRequestProperty("Authorization","Bearer "+KEY);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json");if(body!=null){c.setDoOutput(true);try(OutputStream os=c.getOutputStream()){os.write(body.getBytes(StandardCharsets.UTF_8));}}
    int code=c.getResponseCode();String out=read(code>=200&&code<300?c.getInputStream():c.getErrorStream());if(code<200||code>=300)throw new IOException("HTTP "+code+" "+out);return out;
  }
  public JSONArray publicTrucks()throws Exception{return new JSONArray(call("POST","/rest/v1/rpc/driver_list_trucks","{}"));}
  public JSONObject pair(String truckId,String code)throws Exception{JSONObject b=new JSONObject();b.put("p_truck_id",truckId);b.put("p_pairing_code",code);String out=call("POST","/rest/v1/rpc/driver_pair",b.toString());return new JSONObject(out);}
  public JSONObject startTracking(String truckId,String token)throws Exception{JSONObject b=new JSONObject();b.put("p_truck_id",truckId);b.put("p_device_token",token);return new JSONObject(call("POST","/rest/v1/rpc/driver_start_tracking",b.toString()));}
  public JSONObject sendLocation(String truckId,String token,double lat,double lon,double speed,double accuracy,double distanceKm)throws Exception{JSONObject b=new JSONObject();b.put("p_truck_id",truckId);b.put("p_device_token",token);b.put("p_lat",lat);b.put("p_lon",lon);b.put("p_speed_kmh",speed);b.put("p_accuracy_m",accuracy);b.put("p_distance_km",distanceKm);return new JSONObject(call("POST","/rest/v1/rpc/driver_update_location",b.toString()));}
}
