package edu.multisense.collector;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.*;
import android.net.Uri;
import android.os.*;
import android.webkit.*;
import android.widget.Toast;
import org.json.*;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private WebView web;
    private CaptureService service;
    private boolean bound;
    private JSONObject pendingStart;
    private String exportId;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){service=((CaptureService.LocalBinder)binder).getService();}
        @Override public void onServiceDisconnected(ComponentName name){service=null;}
    };
    @Override public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        web=new WebView(this);setContentView(web);
        web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
        web.getSettings().setAllowFileAccessFromFileURLs(false);web.getSettings().setAllowUniversalAccessFromFileURLs(false);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}});
        web.addJavascriptInterface(new Bridge(),"MultiSenseNative");web.loadUrl("file:///android_asset/index.html");
        bound=bindService(new Intent(this,CaptureService.class),connection,BIND_AUTO_CREATE);
    }
    private final class Bridge {
        @JavascriptInterface public String snapshot(){return service==null?"{\"native\":true,\"busy\":true,\"inventory\":[]}":service.snapshot();}
        @JavascriptInterface public void start(String json){runOnUiThread(()->{try{pendingStart=new JSONObject(json);requestPermissionsAndStart();}catch(Exception e){message("启动失败："+e.getMessage());}});}
        @JavascriptInterface public void stop(){if(service!=null)service.finish();}
        @JavascriptInterface public void marker(String json){try{if(service!=null)service.mark(new JSONObject(json));}catch(Exception e){message("标注失败");}}
        @JavascriptInterface public String sessions(){try{return service==null?"[]":SessionRecorder.list(service.root()).toString();}catch(Exception e){return "[]";}}
        @JavascriptInterface public String review(String id){try{if(service==null)return "{}";if(service.isRecording())return "{\"error\":\"请先结束并保存本次采集\"}";return SessionRecorder.review(service.root(),id).toString();}catch(Exception e){return jsonError(e);}}
        @JavascriptInterface public String page(String id,int offset,String filter){try{return service==null?"{}":SessionRecorder.page(service.root(),id,offset,filter).toString();}catch(Exception e){return jsonError(e);}}
        @JavascriptInterface public void exportSession(String id){runOnUiThread(()->{if(service==null)return;if(service.isRecording()){message("请先结束并保存，再导出记录");return;}try{SessionRecorder.validatedDirectory(service.root(),id);exportId=id;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"MultiSense_"+id+".zip");startActivityForResult(intent,200);}catch(Exception e){message(e.getMessage());}});}
        @JavascriptInterface public void locationSettings(){runOnUiThread(()->startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)));}
        private String jsonError(Exception e){try{return new JSONObject().put("error",e.getMessage()).toString();}catch(Exception ignored){return "{}";}}
    }
    private void requestPermissionsAndStart(){
        if(service==null){message("设备初始化中，请稍后开始");return;}
        if(service.isRecording()){message("本次采集正在进行");return;}
        ArrayList<String> needed=new ArrayList<>();
        if(pendingStart.optBoolean("gnss",true)) {addIfMissing(needed,Manifest.permission.ACCESS_FINE_LOCATION);addIfMissing(needed,Manifest.permission.ACCESS_COARSE_LOCATION);}
        SensorManager manager=(SensorManager)getSystemService(SENSOR_SERVICE);
        if(Build.VERSION.SDK_INT>=29&&(manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)!=null||manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)!=null))addIfMissing(needed,Manifest.permission.ACTIVITY_RECOGNITION);
        if(manager.getDefaultSensor(Sensor.TYPE_HEART_RATE)!=null)addIfMissing(needed,Manifest.permission.BODY_SENSORS);
        if(Build.VERSION.SDK_INT>=33)addIfMissing(needed,Manifest.permission.POST_NOTIFICATIONS);
        if(!needed.isEmpty())requestPermissions(needed.toArray(new String[0]),100);else startCapture();
    }
    private void addIfMissing(List<String> list,String permission){if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)list.add(permission);}
    private void startCapture(){if(service!=null&&pendingStart!=null){service.start(pendingStart);pendingStart=null;}}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==100)startCapture();}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=200||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();String id=exportId;CaptureService capture=service;message("正在导出完整记录…");
        io.execute(()->{try{OutputStream out=getContentResolver().openOutputStream(uri,"w");if(out==null)throw new java.io.IOException("无法打开保存位置");SessionRecorder.export(capture.root(),id,out);message("已导出 ZIP：包含原始数据、CSV、标注和采集信息");}catch(Exception e){message("导出失败："+e.getMessage());}});
    }
    private void message(String text){runOnUiThread(()->{Toast.makeText(this,text,Toast.LENGTH_LONG).show();if(web!=null)web.evaluateJavascript("window.nativeMessage && window.nativeMessage("+JSONObject.quote(text)+")",null);});}
    @Override public void onBackPressed(){web.evaluateJavascript("window.handleBack && window.handleBack()",null);}
    @Override protected void onDestroy(){if(bound)unbindService(connection);web.removeJavascriptInterface("MultiSenseNative");web.destroy();web=null;io.shutdown();super.onDestroy();}
}
