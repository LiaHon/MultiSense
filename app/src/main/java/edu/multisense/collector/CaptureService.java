package edu.multisense.collector;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.*;
import android.location.*;
import android.os.*;
import org.json.*;
import java.io.File;
import java.util.*;

public final class CaptureService extends Service implements SensorEventListener {
    public final class LocalBinder extends Binder { CaptureService getService() { return CaptureService.this; } }
    private final IBinder binder=new LocalBinder();
    private final Object lock=new Object();
    private HandlerThread thread; private Handler worker;
    private SensorManager sensors; private LocationManager locations;
    private PowerManager.WakeLock wakeLock;
    private final IdentityHashMap<Sensor,String> ids=new IdentityHashMap<>();
    private final LinkedHashMap<String,JSONObject> inventory=new LinkedHashMap<>();
    private final LinkedHashMap<String,SampleStats> stats=new LinkedHashMap<>();
    private final HashMap<String,float[]> latest=new HashMap<>();
    private final HashMap<String,Long> latestTime=new HashMap<>(), displayTime=new HashMap<>();
    private final HashMap<String,ArrayDeque<JSONObject>> streams=new HashMap<>();
    private final ArrayDeque<JSONObject> recent=new ArrayDeque<>(), route=new ArrayDeque<>(), markers=new ArrayDeque<>();
    private final ArrayList<String> warnings=new ArrayList<>();
    private SessionRecorder recorder;
    private NetworkCollector networks;
    private final Map<String,Long> networkCounts=new LinkedHashMap<>();
    private boolean recording, busy, locationRegistered, gnssRegistered, rawRegistered, nmeaRegistered;
    private long startedNs, startedWall, totalRows, lastWakeRenew;
    private double endedDuration;
    private int requestedHz=200;
    private String savedId="",error="", label="未标注", initialState="静止";
    private JSONObject lastLocation;
    private JSONArray satellites=new JSONArray();
    private final TriggerEventListener trigger=new TriggerEventListener() {
        @Override public void onTrigger(TriggerEvent e) {
            worker.post(()->{if(!recording)return;recordSensor(e.sensor,e.timestamp,e.values,-1);if(recording)sensors.requestTriggerSensor(this,e.sensor);});
        }
    };
    private final LocationListener locationListener=new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            if(!recording||l.getElapsedRealtimeNanos()<startedNs)return;
            try {
                JSONObject e=event("location",l.getElapsedRealtimeNanos()).put("provider",l.getProvider()).put("lat",l.getLatitude()).put("lon",l.getLongitude());
                if(l.hasAltitude())e.put("alt",l.getAltitude());if(l.hasAccuracy())e.put("accuracy",l.getAccuracy());
                if(l.hasSpeed())e.put("speed",l.getSpeed());if(l.hasBearing())e.put("bearing",l.getBearing());
                if(l.hasVerticalAccuracy())e.put("verticalAccuracy",l.getVerticalAccuracyMeters());
                if(l.hasSpeedAccuracy())e.put("speedAccuracy",l.getSpeedAccuracyMetersPerSecond());
                if(l.hasBearingAccuracy())e.put("bearingAccuracy",l.getBearingAccuracyDegrees());
                synchronized(lock){lastLocation=e;if(route.size()==2000)route.removeFirst();route.add(e);}persist(e);
            }catch(Exception e){fail(e);}
        }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onProviderDisabled(String provider) {warn(provider+" 已关闭，位置记录暂时中断");}
        @Override public void onStatusChanged(String provider,int status,Bundle extras) { }
    };
    private final GnssStatus.Callback gnssCallback=new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus status) {
            if(!recording)return;
            try {
                JSONArray a=new JSONArray();
                for(int i=0;i<status.getSatelliteCount();i++)a.put(new JSONObject().put("svid",status.getSvid(i)).put("constellation",status.getConstellationType(i)).put("cn0",status.getCn0DbHz(i)).put("elevation",status.getElevationDegrees(i)).put("azimuth",status.getAzimuthDegrees(i)).put("used",status.usedInFix(i)));
                synchronized(lock){satellites=a;}persist(event("satellites",SystemClock.elapsedRealtimeNanos()).put("satellites",a));
            }catch(Exception e){fail(e);}
        }
    };
    private final OnNmeaMessageListener nmeaListener=(message,timestamp)->{
        if(!recording)return;try{persist(event("nmea",SystemClock.elapsedRealtimeNanos()).put("sourceWallMs",timestamp).put("sentence",message.trim()));}catch(Exception e){fail(e);}
    };
    private final GnssMeasurementsEvent.Callback rawCallback=new GnssMeasurementsEvent.Callback() {
        @Override public void onGnssMeasurementsReceived(GnssMeasurementsEvent value) {
            if(!recording)return;
            try {
                GnssClock c=value.getClock();JSONObject clock=new JSONObject().put("timeNanos",Long.toString(c.getTimeNanos())).put("hardwareClockDiscontinuityCount",c.getHardwareClockDiscontinuityCount());
                if(c.hasFullBiasNanos())clock.put("fullBiasNanos",Long.toString(c.getFullBiasNanos()));if(c.hasBiasNanos())clock.put("biasNanos",c.getBiasNanos());
                if(c.hasBiasUncertaintyNanos())clock.put("biasUncertaintyNanos",c.getBiasUncertaintyNanos());if(c.hasDriftNanosPerSecond())clock.put("driftNanosPerSecond",c.getDriftNanosPerSecond());
                if(c.hasDriftUncertaintyNanosPerSecond())clock.put("driftUncertaintyNanosPerSecond",c.getDriftUncertaintyNanosPerSecond());
                JSONArray measurements=new JSONArray();
                for(GnssMeasurement m:value.getMeasurements()) {
                    JSONObject o=new JSONObject().put("svid",m.getSvid()).put("constellation",m.getConstellationType()).put("timeOffsetNanos",m.getTimeOffsetNanos()).put("state",m.getState())
                        .put("receivedSvTimeNanos",Long.toString(m.getReceivedSvTimeNanos())).put("receivedSvTimeUncertaintyNanos",Long.toString(m.getReceivedSvTimeUncertaintyNanos())).put("cn0DbHz",m.getCn0DbHz())
                        .put("pseudorangeRateMetersPerSecond",m.getPseudorangeRateMetersPerSecond()).put("pseudorangeRateUncertaintyMetersPerSecond",m.getPseudorangeRateUncertaintyMetersPerSecond())
                        .put("accumulatedDeltaRangeState",m.getAccumulatedDeltaRangeState()).put("accumulatedDeltaRangeMeters",m.getAccumulatedDeltaRangeMeters()).put("accumulatedDeltaRangeUncertaintyMeters",m.getAccumulatedDeltaRangeUncertaintyMeters())
                        .put("multipathIndicator",m.getMultipathIndicator()).put("nativeDescription",m.toString());
                    if(m.hasCarrierFrequencyHz())o.put("carrierFrequencyHz",m.getCarrierFrequencyHz());
                    if(m.hasAutomaticGainControlLevelDb())o.put("agcDb",m.getAutomaticGainControlLevelDb());
                    measurements.put(o);
                }
                persist(event("gnss_raw",SystemClock.elapsedRealtimeNanos()).put("clock",clock.put("nativeDescription",c.toString())).put("measurements",measurements));
            }catch(Exception e){fail(e);}
        }
        @Override public void onStatusChanged(int status) {if(status==STATUS_NOT_SUPPORTED)warn("该设备不支持 GNSS 原始观测");}
    };

    @Override public void onCreate() {
        super.onCreate();thread=new HandlerThread("multisense-capture");thread.start();worker=new Handler(thread.getLooper());
        sensors=(SensorManager)getSystemService(SENSOR_SERVICE);locations=(LocationManager)getSystemService(LOCATION_SERVICE);
        int index=0;
        for(Sensor s:sensors.getSensorList(Sensor.TYPE_ALL)){String id=String.format(Locale.US,"s%03d",index++);ids.put(s,id);inventory.put(id,SensorCatalog.describe(s,id));}
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("capture","数据采集",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){if(intent!=null&&"stop".equals(intent.getAction()))finish();return START_NOT_STICKY;}
    File root(){File root=new File(getFilesDir(),"sessions");root.mkdirs();return root;}
    boolean isRecording(){synchronized(lock){return recording||busy;}}
    void start(JSONObject options) {
        synchronized(lock){if(recording||busy)return;busy=true;error="";}
        boolean gps=(options.optBoolean("gnss",true)||options.optBoolean("wifi",true)||options.optBoolean("ble",true)||options.optBoolean("cell",true))&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        try {
            Intent notificationIntent=new Intent(this,MainActivity.class);
            PendingIntent show=PendingIntent.getActivity(this,0,notificationIntent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,CaptureService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n=new Notification.Builder(this,"capture").setSmallIcon(edu.multisense.collector.R.drawable.ic_app).setContentTitle("MultiSense 正在采集")
                .setContentText("传感器数据持续保存到本机，点此查看").setOngoing(true).setContentIntent(show).addAction(new Notification.Action.Builder(null,"结束并保存",stop).build()).build();
            startService(new Intent(this,CaptureService.class));
            if(Build.VERSION.SDK_INT>=34)startForeground(100,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE|(gps?ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION:0));
            else startForeground(100,n);
            worker.post(()->begin(options));
        }catch(Exception e){synchronized(lock){busy=false;error="无法启动采集："+e.getMessage();}stopForeground(STOP_FOREGROUND_REMOVE);}
    }
    private void begin(JSONObject options) {
        try {
            synchronized(lock){stats.clear();latest.clear();latestTime.clear();displayTime.clear();streams.clear();recent.clear();route.clear();markers.clear();warnings.clear();lastLocation=null;satellites=new JSONArray();savedId="";totalRows=0;endedDuration=0;label="未标注";initialState="静止";
                for(JSONObject o:inventory.values()){o.put("state","未选择");o.remove("requestedPeriodUs");}
                networkCounts.clear();networks=null;
                startedNs=SystemClock.elapsedRealtimeNanos();startedWall=System.currentTimeMillis();requestedHz=SamplingPolicy.motionHz(options.optInt("hz",200));
                options.put("hz",requestedHz);label=options.optString("initialState","静止").trim();if(label.isEmpty())label="静止";options.put("initialState",label);initialState=label;}
            recorder=new SessionRecorder(root(),options,inventoryArray(),startedNs,startedWall);
            synchronized(lock){recording=true;busy=false;}
            wakeLock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MultiSense:recording");
            wakeLock.setReferenceCounted(false);wakeLock.acquire(600_000);lastWakeRenew=SystemClock.elapsedRealtime();
            Set<String> selected=new HashSet<>();JSONArray selection=options.optJSONArray("sensorIds");if(selection!=null)for(int i=0;i<selection.length();i++)selected.add(selection.optString(i));
            int registered=0;
            for(var entry:ids.entrySet()) {
                Sensor sensor=entry.getKey();String id=entry.getValue();if(selection!=null&&!selected.contains(id))continue;
                try {
                    int period=SamplingPolicy.periodUs(sensor.getType(),requestedHz,sensor.getReportingMode());
                    boolean ok=sensor.getReportingMode()==Sensor.REPORTING_MODE_ONE_SHOT?sensors.requestTriggerSensor(trigger,sensor):sensors.registerListener(this,sensor,period,0,worker);
                    synchronized(lock){inventory.get(id).put("state",ok?"采集中":"无法注册").put("requestedPeriodUs",period);if(ok){stats.put(id,new SampleStats());registered++;}}
                    if(!ok)warn(SensorCatalog.name(sensor.getType())+" 无法注册");
                }catch(Exception e){synchronized(lock){inventory.get(id).put("state","权限或系统限制");}warn(SensorCatalog.name(sensor.getType())+"："+e.getClass().getSimpleName());}
            }
            if(options.optBoolean("gnss",true))registerLocation();
            networks=new NetworkCollector(this,worker,startedNs,new NetworkCollector.Sink() {
                @Override public void sample(long ns,JSONObject fields) { if(!recording)return;try {JSONObject e=event("network",ns);for(Iterator<String> keys=fields.keys();keys.hasNext();) {String key=keys.next();e.put(key,fields.get(key));}persist(e);synchronized(lock){String source=fields.getString("source");networkCounts.put(source,networkCounts.getOrDefault(source,0L)+1);}}catch(Exception e){fail(e);} }
                @Override public void warning(String text) {warn(text);}
            });
            boolean networkRegistered=networks.start(options);
            if(registered==0&&!locationRegistered&&!networkRegistered)throw new IllegalStateException("没有可采集的数据源，请检查权限和传感器选择");
            worker.postDelayed(checkpoint,2000);
        }catch(Exception e){fail(e);}
    }
    private void registerLocation() {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){warn("未获精确定位权限，GNSS 未采集");return;}
        try {
            if(locations.isProviderEnabled(LocationManager.GPS_PROVIDER)){locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,locationListener,worker.getLooper());locationRegistered=true;}
            else warn("GNSS 定位开关未开启");
            if(locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,1000,0,locationListener,worker.getLooper());locationRegistered=true;}
            gnssRegistered=locations.registerGnssStatusCallback(gnssCallback,worker);
            rawRegistered=locations.registerGnssMeasurementsCallback(rawCallback,worker);
            nmeaRegistered=locations.addNmeaListener(nmeaListener,worker);
            if(!rawRegistered)warn("GNSS 原始观测不可用");
        }catch(Exception e){warn("GNSS："+e.getMessage());}
    }
    @Override public void onSensorChanged(SensorEvent e){if(recording)recordSensor(e.sensor,e.timestamp,e.values.clone(),e.accuracy);}
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy) { }
    private void recordSensor(Sensor sensor,long time,float[] values,int accuracy) {
        String id=ids.get(sensor);if(id==null||time<startedNs)return;
        try {
            JSONArray v=new JSONArray();for(float value:values)v.put(Float.isFinite(value)?value:JSONObject.NULL);
            JSONObject e=event("sensor",time).put("id",id).put("type",sensor.getType()).put("accuracy",accuracy).put("values",v);
            synchronized(lock){SampleStats stat=stats.get(id);if(stat==null)return;stat.add(time,values);latest.put(id,values);latestTime.put(id,time);
                if(time-displayTime.getOrDefault(id,0L)>=40_000_000L){displayTime.put(id,time);ArrayDeque<JSONObject> s=streams.computeIfAbsent(id,k->new ArrayDeque<>());if(s.size()==300)s.removeFirst();s.add(new JSONObject().put("s",e.getDouble("s")).put("values",v));}}
            persist(e);
        }catch(Exception e){fail(e);}
    }
    private JSONObject event(String kind,long time)throws JSONException{return new JSONObject().put("kind",kind).put("t",Long.toString(time)).put("s",(time-startedNs)/1e9).put("wall",startedWall+(time-startedNs)/1_000_000).put("label",label);}
    private void persist(JSONObject e)throws Exception {
        recorder.write(e);synchronized(lock){totalRows++;if(recent.size()==80)recent.removeFirst();recent.add(e);}
    }
    void mark(JSONObject input){worker.post(()->{if(!recording)return;try{String nextLabel=input.optString("label","标记").trim();if(nextLabel.isEmpty())throw new IllegalArgumentException("标注名称不能为空");boolean changesState=input.optBoolean("changesState",true);JSONObject e=event("marker",SystemClock.elapsedRealtimeNanos()).put("label",nextLabel).put("changesState",changesState).put("note",input.optString("note",""));
        if(input.has("refLat")&&input.has("refLon")){double lat=input.getDouble("refLat"),lon=input.getDouble("refLon");if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)throw new IllegalArgumentException("参考坐标超出范围");e.put("refLat",lat).put("refLon",lon);}
        persist(e);synchronized(lock){if(changesState)label=nextLabel;if(markers.size()==5000)markers.removeFirst();markers.add(e);}}catch(Exception e){warn("标注失败："+e.getMessage());}});}
    void finish(){worker.post(()->complete("saved"));}
    private void complete(String status) {
        synchronized(lock){if(!recording&&!busy&&recorder==null)return;recording=false;busy=true;endedDuration=startedNs==0?0:(SystemClock.elapsedRealtimeNanos()-startedNs)/1e9;}
        worker.removeCallbacks(checkpoint);sensors.unregisterListener(this);for(Sensor s:ids.keySet())sensors.cancelTriggerSensor(trigger,s);
        if(networks!=null)networks.stop();
        try{if(locationRegistered)locations.removeUpdates(locationListener);if(gnssRegistered)locations.unregisterGnssStatusCallback(gnssCallback);if(rawRegistered)locations.unregisterGnssMeasurementsCallback(rawCallback);if(nmeaRegistered)locations.removeNmeaListener(nmeaListener);}catch(Exception e){warn(e.getMessage());}
        locationRegistered=gnssRegistered=rawRegistered=nmeaRegistered=false;
        try{if(recorder!=null){recorder.manifest.put("warnings",new JSONArray(warnings)).put("networkStatus",networks==null?new JSONObject():networks.snapshot()).put("networkCounts",new JSONObject(networkCounts));recorder.finish(statistics(),endedDuration,status);savedId=recorder.directory.getName();}}catch(Exception e){synchronized(lock){error="保存未完整完成："+e.getMessage();}}
        recorder=null;if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();wakeLock=null;
        synchronized(lock){busy=false;for(JSONObject o:inventory.values())try{o.put("state","已停止");}catch(Exception ignored){}}
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    private void fail(Exception e){synchronized(lock){error="采集已中止："+e.getMessage();}complete("interrupted");}
    private void warn(String message){synchronized(lock){if(message!=null&&!warnings.contains(message))warnings.add(message);}}
    private final Runnable checkpoint=new Runnable(){public void run(){if(!recording)return;try{if(wakeLock!=null&&SystemClock.elapsedRealtime()-lastWakeRenew>300_000){wakeLock.acquire(600_000);lastWakeRenew=SystemClock.elapsedRealtime();}recorder.flush();recorder.manifest.put("warnings",new JSONArray(warnings));recorder.checkpoint(statistics(),(SystemClock.elapsedRealtimeNanos()-startedNs)/1e9,null);worker.postDelayed(this,5000);}catch(Exception e){fail(e);}}};
    private JSONArray inventoryArray(){synchronized(lock){JSONArray result=new JSONArray();for(JSONObject o:inventory.values())result.put(o);return result;}}
    private JSONArray statistics(){synchronized(lock){double duration=recording?(SystemClock.elapsedRealtimeNanos()-startedNs)/1e9:endedDuration;JSONArray a=new JSONArray();for(var entry:stats.entrySet()){SampleStats s=entry.getValue();try{a.put(new JSONObject().put("id",entry.getKey()).put("count",s.count).put("hz",s.hz()).put("sessionHz",duration>0?s.count/duration:0).put("maxGapMs",s.maxGapNs/1e6).put("nonMonotonic",s.nonMonotonic).put("meanV0",s.mean).put("varianceV0",s.variance()));}catch(Exception ignored){}}return a;}}
    String snapshot(){synchronized(lock){try {
        JSONObject stream=new JSONObject(),values=new JSONObject();for(var e:streams.entrySet())stream.put(e.getKey(),new JSONArray(e.getValue()));
        for(var e:latest.entrySet()){JSONArray v=new JSONArray();for(float f:e.getValue())v.put(Float.isFinite(f)?f:JSONObject.NULL);values.put(e.getKey(),new JSONObject().put("values",v).put("ageMs",(SystemClock.elapsedRealtimeNanos()-latestTime.get(e.getKey()))/1e6));}
        return new JSONObject().put("native",true).put("recording",recording).put("busy",busy).put("error",error).put("savedId",savedId).put("sessionId",recorder==null?savedId:recorder.directory.getName())
            .put("duration",recording?(SystemClock.elapsedRealtimeNanos()-startedNs)/1e9:endedDuration).put("rows",totalRows).put("requestedHz",requestedHz).put("label",label)
            .put("inventory",inventoryArray()).put("stats",statistics()).put("latest",values).put("streams",stream).put("raw",new JSONArray(recent)).put("route",new JSONArray(route)).put("markers",new JSONArray(markers))
            .put("initialState",initialState).put("networkStatus",networks==null?new JSONObject():networks.snapshot()).put("networkCounts",new JSONObject(networkCounts))
            .put("location",lastLocation==null?JSONObject.NULL:lastLocation).put("satellites",satellites).put("warnings",new JSONArray(warnings)).put("device",Build.MANUFACTURER+" "+Build.MODEL).toString();
    }catch(Exception e){return "{}";}}}
    @Override public void onDestroy(){if(recording||busy){worker.post(()->{complete("interrupted");thread.quitSafely();});}else thread.quitSafely();super.onDestroy();}
}
