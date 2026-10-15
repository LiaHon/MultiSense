package edu.multisense.collector;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.*;
import android.telephony.*;
import org.json.*;
import java.util.*;

/** One instance per session. Callbacks and teardown run on the capture looper. */
@SuppressWarnings("deprecation")
final class NetworkCollector {
    interface Sink { void sample(long ns, JSONObject fields); void warning(String text); }
    private final Context context;
    private final Handler handler;
    private final Sink sink;
    private final long startNs;
    private final Map<String,Long> seen = new HashMap<>();
    private final JSONObject status = new JSONObject();
    private WifiManager wifi;
    private BluetoothLeScanner ble;
    private TelephonyManager phone;
    private PhoneStateListener phoneListener;
    private boolean active, receiverRegistered, bleRegistered, cellRegistered, cellListening;

    NetworkCollector(Context context, Handler handler, long startNs, Sink sink) {
        this.context=context; this.handler=handler; this.startNs=startNs; this.sink=sink;
    }
    private boolean granted(String p) { return context.checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED; }
    private synchronized void state(String source, String text) { try { status.put(source,text); } catch(JSONException ignored) {} }
    synchronized JSONObject snapshot() { try { return new JSONObject(status.toString()); } catch(JSONException e) { return new JSONObject(); } }
    private void unavailable(String source, String text) { state(source,text); sink.warning(source+"："+text); }
    boolean start(JSONObject options) {
        active=true;
        boolean fine=granted(Manifest.permission.ACCESS_FINE_LOCATION);
        LocationManager location=(LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
        boolean locationOn=Build.VERSION.SDK_INT>=28?location.isLocationEnabled():location.isProviderEnabled(LocationManager.GPS_PROVIDER)||location.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        for(String source:Arrays.asList("wifi","ble","cell")) {
            if(!options.optBoolean(source,true)) { state(source,"未选择"); continue; }
            if(!fine || !locationOn) { unavailable(source,"需要精确定位权限及系统定位开关"); continue; }
            try {
                switch(source) {
                    case "wifi" -> {
                        wifi=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                        if(wifi==null || (!wifi.isWifiEnabled()&&!wifi.isScanAlwaysAvailable())) { wifi=null; unavailable(source,"Wi-Fi 扫描未开启"); break; }
                        IntentFilter filter=new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
                        if(Build.VERSION.SDK_INT>=33) context.registerReceiver(receiver,filter,null,handler,Context.RECEIVER_EXPORTED);
                        else context.registerReceiver(receiver,filter,null,handler);
                        receiverRegistered=true; handler.post(scanWifi);
                    }
                    case "ble" -> {
                        if(Build.VERSION.SDK_INT>=31 && (!granted(Manifest.permission.BLUETOOTH_SCAN)||!granted(Manifest.permission.BLUETOOTH_CONNECT))) { unavailable(source,"未获附近设备权限"); break; }
                        BluetoothManager manager=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
                        BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
                        if(adapter==null||!adapter.isEnabled()) { unavailable(source,"蓝牙不支持或未开启"); break; }
                        ble=adapter.getBluetoothLeScanner();
                        if(ble==null) { unavailable(source,"BLE 扫描不可用"); break; }
                        // Unfiltered continuous scan; record at most one advertisement/device/second.
                        ble.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build(),bleCallback);
                        bleRegistered=true;
                        state(source,"扫描中 · 每设备最多 1 Hz");
                    }
                    case "cell" -> {
                        if(!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) { unavailable(source,"设备无蜂窝模块"); break; }
                        phone=(TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
                        if(phone==null) { unavailable(source,"系统蜂窝服务不可用"); break; }
                        // LISTEN_CELL_INFO additionally requires READ_PHONE_STATE. A denied listener
                        // must not prevent requestCellInfoUpdate, whose permission is fine location.
                        if(granted(Manifest.permission.READ_PHONE_STATE)) {
                            try {
                                phoneListener=new PhoneStateListener() { @Override public void onCellInfoChanged(List<CellInfo> cells) { recordCells(cells); } };
                                phone.listen(phoneListener,PhoneStateListener.LISTEN_CELL_INFO);
                                cellListening=true;
                            } catch(SecurityException e) { sink.warning("蜂窝变化监听被系统拒绝，改用定时更新；请检查电话与精确定位权限"); }
                            catch(Exception e) { sink.warning("蜂窝变化监听不可用，改用定时更新："+e.getClass().getSimpleName()); }
                        } else sink.warning("未获电话状态权限（READ_PHONE_STATE），蜂窝改用定时更新；可在应用权限中允许“电话”后重新采集");
                        cellRegistered=true;
                        handler.post(scanCell); state(source,cellListening?"监听基站变化 · 30 秒定时更新":"仅 30 秒定时更新 · 电话状态监听未启用");
                    }
                }
            } catch(SecurityException e) { unavailable(source,"权限被拒绝或已撤销"); }
            catch(Exception e) { unavailable(source,e.getClass().getSimpleName()+" · "+String.valueOf(e.getMessage())); }
        }
        return receiverRegistered || bleRegistered || cellRegistered;
    }
    private final Runnable scanWifi=new Runnable() { public void run() {
        if(!active||wifi==null)return;
        try { state("wifi",wifi.startScan()?"已请求扫描 · 30 秒周期":"扫描请求受限 · 等待系统新结果"); }
        catch(Exception e) { unavailable("wifi",e.getClass().getSimpleName()); }
        handler.postDelayed(this,30_000);
    }};
    private final BroadcastReceiver receiver=new BroadcastReceiver() { @Override public void onReceive(Context c, Intent intent) {
        if(!active||!intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED,false))return;
        try {
            int count=0;
            for(android.net.wifi.ScanResult r:wifi.getScanResults()) {
                long ns=r.timestamp*1000;
                if(ns<startNs||ns<=seen.getOrDefault("wifi:"+r.BSSID,0L))continue;
                seen.put("wifi:"+r.BSSID,ns);
                emit(ns,new JSONObject().put("source","wifi").put("address",r.BSSID).put("name",r.SSID).put("rssiDbm",r.level).put("frequencyMhz",r.frequency).put("capabilities",r.capabilities)); count++;
            }
            state("wifi","收到 "+count+" 条新 AP 观测");
        } catch(SecurityException e) { unavailable("wifi","权限被拒绝或已撤销"); }
        catch(Exception e) { unavailable("wifi",e.getClass().getSimpleName()); }
    }};
    private final ScanCallback bleCallback=new ScanCallback() {
        @Override public void onScanResult(int type,android.bluetooth.le.ScanResult r) { handler.post(()->recordBle(r)); }
        @Override public void onBatchScanResults(List<android.bluetooth.le.ScanResult> results) { handler.post(()->{ for(var r:results)recordBle(r); }); }
        @Override public void onScanFailed(int code) { handler.post(()->{if(active)unavailable("ble","扫描失败 · 代码 "+code);}); }
    };
    private void recordBle(android.bluetooth.le.ScanResult r) {
        if(!active||r.getTimestampNanos()<startNs)return;
        try {
            String address=r.getDevice().getAddress(); long ns=r.getTimestampNanos();
            if(ns-seen.getOrDefault("ble:"+address,-1_000_000_000L)<1_000_000_000L)return;
            seen.put("ble:"+address,ns); ScanRecord record=r.getScanRecord();
            emit(ns,new JSONObject().put("source","ble").put("address",address).put("name",record==null?"":record.getDeviceName()).put("rssiDbm",r.getRssi())
                .put("advertisementBase64",record==null?"":android.util.Base64.encodeToString(record.getBytes(),android.util.Base64.NO_WRAP)));
            state("ble","扫描中 · 已发现 "+seen.keySet().stream().filter(k->k.startsWith("ble:")).count()+" 个设备");
        } catch(SecurityException e) { unavailable("ble","权限被拒绝或已撤销"); }
        catch(Exception e) { unavailable("ble",e.getClass().getSimpleName()); }
    }
    private final Runnable scanCell=new Runnable() { public void run() {
        if(!active||phone==null)return;
        if(!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            unavailable("cell","缺少精确定位权限，请在应用权限中启用“精确位置”后重新采集");
            handler.postDelayed(this,30_000);return;
        }
        try {
            if(Build.VERSION.SDK_INT>=29)phone.requestCellInfoUpdate(handler::post,new TelephonyManager.CellInfoCallback() {
                @Override public void onCellInfo(List<CellInfo> cells) { recordCells(cells); }
                @Override public void onError(int code,Throwable detail) { if(active)state("cell","更新受限 · 代码 "+code); }
            });
            else recordCells(phone.getAllCellInfo());
        } catch(SecurityException e) { unavailable("cell","系统拒绝基站更新，请检查精确定位权限、系统定位开关及应用权限限制"); }
        catch(Exception e) { unavailable("cell",e.getClass().getSimpleName()); }
        handler.postDelayed(this,30_000);
    }};
    private void recordCells(List<CellInfo> cells) {
        if(!active||cells==null)return;
        try {
            for(CellInfo c:cells) {
                long ns=c.getTimeStamp(); if(ns<startNs)continue;
                String id; int dbm; long cellId;
                if(c instanceof CellInfoLte v) { id=v.getCellIdentity().toString(); dbm=v.getCellSignalStrength().getDbm(); cellId=v.getCellIdentity().getCi(); }
                else if(c instanceof CellInfoGsm v) { id=v.getCellIdentity().toString(); dbm=v.getCellSignalStrength().getDbm(); cellId=v.getCellIdentity().getCid(); }
                else if(c instanceof CellInfoWcdma v) { id=v.getCellIdentity().toString(); dbm=v.getCellSignalStrength().getDbm(); cellId=v.getCellIdentity().getCid(); }
                else if(c instanceof CellInfoCdma v) { id=v.getCellIdentity().toString(); dbm=v.getCellSignalStrength().getDbm(); cellId=v.getCellIdentity().getBasestationId(); }
                else if(Build.VERSION.SDK_INT>=29 && c instanceof CellInfoNr v) { id=v.getCellIdentity().toString(); dbm=v.getCellSignalStrength().getDbm(); cellId=((CellIdentityNr)v.getCellIdentity()).getNci(); }
                else if(Build.VERSION.SDK_INT>=29 && c instanceof CellInfoTdscdma v) { id=v.getCellIdentity().toString(); dbm=v.getCellSignalStrength().getDbm(); cellId=v.getCellIdentity().getCid(); }
                else continue;
                String key="cell:"+id; if(ns<=seen.getOrDefault(key,0L))continue; seen.put(key,ns);
                JSONObject fields=new JSONObject().put("source","cell").put("address",id).put("radioType",c.getClass().getSimpleName()).put("registered",c.isRegistered()).put("nativeDescription",c.toString());
                if(cellId!=Integer.MAX_VALUE&&cellId!=Long.MAX_VALUE)fields.put("cellId",Long.toString(cellId));
                if(dbm!=CellInfo.UNAVAILABLE)fields.put("rssiDbm",dbm);
                emit(ns,fields);
            }
            state("cell",(cellListening?"监听 + 定时更新":"仅定时更新")+" · 最近上报 "+cells.size()+" 个基站");
        } catch(SecurityException e) { unavailable("cell","权限被拒绝或已撤销"); }
        catch(Exception e) { unavailable("cell",e.getClass().getSimpleName()); }
    }
    private void emit(long ns,JSONObject fields) { if(active)sink.sample(ns,fields); }
    void stop() {
        active=false; handler.removeCallbacks(scanWifi); handler.removeCallbacks(scanCell);
        try { if(receiverRegistered)context.unregisterReceiver(receiver); } catch(Exception ignored) {} receiverRegistered=false;
        try { if(ble!=null)ble.stopScan(bleCallback); } catch(SecurityException ignored) {} catch(Exception ignored) {}
        try { if(phone!=null&&phoneListener!=null)phone.listen(phoneListener,PhoneStateListener.LISTEN_NONE); } catch(Exception ignored) {}
        cellListening=false;
    }
}
