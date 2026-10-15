package edu.multisense.collector;

import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.*;

/** Only accessed on the capture thread. All original events go to disk, UI buffers are separate. */
final class SessionRecorder {
    final File directory;
    final JSONObject manifest;
    private final Map<String, BufferedWriter> writers = new LinkedHashMap<>();
    private long lastFlush;
    private final Map<String,Long> eventCounts = new LinkedHashMap<>();
    long rows;
    private String currentState;
    private double stateStart;
    private int stateSegments;
    private final Set<String> stateNames = new LinkedHashSet<>();

    SessionRecorder(File root, JSONObject options, JSONArray inventory, long startNs, long startWallMs) throws Exception {
        String id = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(startWallMs)) + "_" + UUID.randomUUID().toString().substring(0, 6);
        directory = new File(root, id);
        if (!directory.mkdirs()) throw new IOException("无法创建采集目录");
        currentState = options.optString("action", options.optString("initialState", "静止"));
        manifest = new JSONObject().put("schemaVersion", 2).put("id", id)
            .put("title", options.optString("title", "步行采集"))
            .put("scene", options.optString("scene", "室内")).put("action", options.optString("action",options.optString("initialState","静止"))).put("defaultScene", options.optString("scene","室内")).put("defaultAction", options.optString("action",options.optString("initialState","静止")))
            .put("pose", options.optString("pose", "手持固定"))
            .put("requestedHz", options.optInt("hz", 200)).put("gnssRequested", options.optBoolean("gnss", true))
            .put("networkRequested",new JSONObject().put("wifi",options.optBoolean("wifi",true)).put("ble",options.optBoolean("ble",true)).put("cell",options.optBoolean("cell",true)))
            .put("initialState",currentState)
            .put("startedWallMs", startWallMs).put("startedElapsedNs", startNs)
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("appVersion", "1.1.2").put("status", "recording").put("sensors", inventory)
            .put("timebase", "elapsed_realtime_ns; sensor timestamps preserved; wall_ms derived from session start")
            .put("coordinates", "Android device frame: X right, Y towards top, Z out of screen. GNSS WGS84.")
            .put("notes", "未采样与丢失数据不补零；传感器异步记录。on-change / trigger 数据不应按连续采样率判断。");
        open("samples.jsonl", "");
        open("sensors.csv", Csv.row("elapsed_ns", "relative_s", "wall_ms", "sensor_id", "sensor_type", "accuracy", "v0", "v1", "v2", "v3", "v4", "v5", "values_json", "scene", "action"));
        open("locations.csv", Csv.row("elapsed_ns", "relative_s", "wall_ms", "provider", "latitude_deg", "longitude_deg", "altitude_m", "horizontal_accuracy_m", "vertical_accuracy_m", "speed_m_s", "speed_accuracy_m_s", "bearing_deg", "bearing_accuracy_deg"));
        open("satellites.csv", Csv.row("elapsed_ns", "relative_s", "svid", "constellation", "cn0_dbhz", "elevation_deg", "azimuth_deg", "used_in_fix"));
        open("nmea.csv", Csv.row("received_elapsed_ns", "source_wall_ms", "sentence"));
        open("markers.csv", Csv.row("elapsed_ns", "relative_s", "label", "note", "reference_latitude_deg", "reference_longitude_deg"));
        open("states.csv", Csv.row("elapsed_ns","relative_s","wall_ms","scene","action"));
        open("network.csv", Csv.row("elapsed_ns", "relative_s", "wall_ms", "source", "address", "name", "rssi_dbm", "frequency_mhz", "details_json"));
        // Deliberately headerless: assignment format is start_s,end_s,state on every line.
        open("labels.csv", "");
        checkpoint(new JSONArray(), 0, null);
    }
    private void open(String name, String header) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(directory, name)), StandardCharsets.UTF_8), 65536);
        writers.put(name, writer); writer.write(header);
    }
    void write(JSONObject event) throws Exception {
        writers.get("samples.jsonl").write(event.toString()); writers.get("samples.jsonl").write('\n'); rows++;
        String kind = event.getString("kind");
        eventCounts.put(kind,eventCounts.getOrDefault(kind,0L)+1);
        switch (kind) {
            case "sensor" -> {
                JSONArray v = event.getJSONArray("values");
                Object[] cells = new Object[15];
                cells[0] = event.getLong("t"); cells[1] = event.getDouble("s"); cells[2] = event.getLong("wall");
                cells[3] = event.getString("id"); cells[4] = event.getInt("type"); cells[5] = event.getInt("accuracy");
                for (int i = 0; i < 6; i++) cells[i + 6] = i < v.length() ? v.opt(i) : "";
                cells[12] = v.toString();cells[13]=event.optString("scene");cells[14]=event.optString("action"); writers.get("sensors.csv").write(Csv.row(cells));
            }
            case "location" -> writers.get("locations.csv").write(Csv.row(event.getLong("t"), event.getDouble("s"), event.getLong("wall"), event.opt("provider"), event.opt("lat"), event.opt("lon"), nullable(event,"alt"), nullable(event,"accuracy"), nullable(event,"verticalAccuracy"), nullable(event,"speed"), nullable(event,"speedAccuracy"), nullable(event,"bearing"), nullable(event,"bearingAccuracy")));
            case "satellites" -> {
                JSONArray satellites = event.getJSONArray("satellites");
                for (int i = 0; i < satellites.length(); i++) {
                    JSONObject o = satellites.getJSONObject(i);
                    writers.get("satellites.csv").write(Csv.row(event.getLong("t"), event.getDouble("s"), o.opt("svid"), o.opt("constellation"), o.opt("cn0"), o.opt("elevation"), o.opt("azimuth"), o.opt("used")));
                }
            }
            case "nmea" -> writers.get("nmea.csv").write(Csv.row(event.getLong("t"), event.opt("sourceWallMs"), event.opt("sentence")));
            case "marker" -> {
                writers.get("markers.csv").write(Csv.row(event.getLong("t"), event.getDouble("s"), event.opt("label"), nullable(event,"note"), nullable(event,"refLat"), nullable(event,"refLon")));
                if(event.optBoolean("changesState",false)) {
                    String next = event.getString("label"); double at = event.getDouble("s");
                    if(!next.equals(currentState)) { closeSegment(at); currentState=next; stateStart=at; }
                }
            }
            case "state" -> {
                writers.get("states.csv").write(Csv.row(event.getLong("t"),event.getDouble("s"),event.optLong("wall",0L),event.optString("scene"),event.optString("action")));
                manifest.put("scene", event.optString("scene", manifest.optString("scene")));
                manifest.put("action", event.optString("action", manifest.optString("action")));
                String next=event.optString("action",currentState);double at=event.getDouble("s");
                if(!next.equals(currentState)){closeSegment(at);currentState=next;stateStart=at;}
            }
            case "network" -> writers.get("network.csv").write(Csv.row(event.getLong("t"),event.getDouble("s"),event.getLong("wall"),event.opt("source"),event.opt("address"),nullable(event,"name"),nullable(event,"rssiDbm"),nullable(event,"frequencyMhz"),event.toString()));
        }
        if (System.currentTimeMillis() - lastFlush > 2000) flush();
    }
    private Object nullable(JSONObject o, String key) { return o.isNull(key) ? "" : o.opt(key); }
    void flush() throws IOException { for (BufferedWriter w : writers.values()) w.flush(); lastFlush = System.currentTimeMillis(); }
    void checkpoint(JSONArray stats, double duration, String status) throws Exception {
        manifest.put("rows", rows).put("durationS", duration).put("statistics", stats).put("eventCounts",new JSONObject(eventCounts));
        if (status != null) manifest.put("status", status);
        File temp = new File(directory, "manifest.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
        Files.move(temp.toPath(), new File(directory, "manifest.json").toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    void finish(JSONArray stats, double duration, String status) throws Exception {
        closeSegment(duration);
        IOException failure = null;
        for (BufferedWriter w : writers.values()) { try { w.close(); } catch (IOException e) { failure = e; } }
        if (failure != null) throw failure;
        manifest.put("endedWallMs", System.currentTimeMillis()).put("stateNames",new JSONArray(stateNames)).put("stateSegments",stateSegments);
        writeSummary(stats,duration,status);
        checkpoint(stats, duration, status);
    }
    private void closeSegment(double end) throws IOException {
        if(end<=stateStart)return;
        writers.get("labels.csv").write(Csv.row(stateStart,end,currentState));
        stateNames.add(currentState); stateSegments++;
    }
    private void writeSummary(JSONArray stats,double duration,String status) throws Exception {
        JSONArray motion=new JSONArray(); StringBuilder table=new StringBuilder();
        JSONArray sensors=manifest.getJSONArray("sensors");
        boolean motionPass=true; Set<Integer> types=new HashSet<>();
        for(int i=0;i<sensors.length();i++) {
            JSONObject sensor=sensors.getJSONObject(i); JSONObject stat=null;
            for(int j=0;j<stats.length();j++)if(stats.getJSONObject(j).getString("id").equals(sensor.getString("id"))) { stat=stats.getJSONObject(j); break; }
            long count=stat==null?0:stat.optLong("count"); double hz=duration>0?count/duration:0;
            table.append("| ").append(sensor.getString("id")).append(" · ").append(sensor.optString("title",sensor.optString("type"))).append(" | ").append(count).append(" | ").append(String.format(Locale.US,"%.3f",hz)).append(" | ").append(sensor.optString("state","未知")).append(" |\n");
            if(SamplingPolicy.isMotion(sensor.optInt("type"))) {
                types.add(sensor.getInt("type")); boolean pass=hz>=100; motionPass&=pass;
                motion.put(new JSONObject().put("id",sensor.getString("id")).put("type",sensor.getInt("type")).put("sessionHz",hz).put("pass",pass).put("count",count));
            }
        }
        JSONArray missing=new JSONArray();
        for(int type:new int[]{1,4,9,10})if(!types.contains(type)) {missing.put(type);motionPass=false;}
        JSONObject check=new JSONObject().put("scope","采集检查；不含 FFT、分类器或完整作业 self_test")
            .put("durationS",duration).put("durationPass",duration>=1200).put("stateCount",stateNames.size()).put("statesPass",stateNames.size()>=3)
            .put("motionSensors",motion).put("missingMotionTypes",missing).put("motionRatePass",motionPass).put("finishedNormally",status.equals("saved"))
            .put("continuityNeedsReview",true).put("note","正常结束不能证明全程无间断；请结合 maxGapMs、时间戳异常与全程波形核验。缺失或未选运动通道不视为达标。");
        Files.write(new File(directory,"collection_check.json").toPath(),check.toString(2).getBytes(StandardCharsets.UTF_8));
        String text="# 采集数据规格\n\n设备："+Build.MANUFACTURER+" "+Build.MODEL+"（Android SDK "+Build.VERSION.SDK_INT+"）\n\n场景："+manifest.optString("scene")+"；携带位置："+manifest.optString("pose")+"\n\n时长："+String.format(Locale.US,"%.3f",duration)+" 秒；结束状态："+status+"；状态种类："+stateNames.size()+"\n\n实测频率 = 总样本数 / 全次采集时长；事件型与慢变量不按 100 Hz 验收。\n\n| 通道 | 样本数 | 全程实测 Hz | 采集状态 |\n| --- | --- | --- | --- |\n"+table+"\nlabels.csv：无表头，起始秒,结束秒,状态名；区间左闭右开。markers.csv：关键事件及参考点。\n\nnetwork.csv：Wi-Fi/BLE/蜂窝独立观测；系统节流、权限或开关可能使记录为空，详见 manifest.json 的 warnings。\n\n时间为 elapsed realtime 纳秒及推算系统毫秒，传感器采用 Android 机体系，GNSS 为 WGS84。分析前按时间排序；原始数据本地留存，不纳入版本库。\n\n采集检查见 collection_check.json；仍须人工核对全程连续性与标签，完成特征分析和课程 self_test。\n";
        Files.write(new File(directory,"data_spec.md").toPath(),text.getBytes(StandardCharsets.UTF_8));
    }
    static JSONArray list(File root) throws Exception {
        JSONArray result = new JSONArray(); File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return result;
        Arrays.sort(dirs, (a, b) -> b.getName().compareTo(a.getName()));
        for (File d : dirs) {
            File metadata = new File(d,"manifest.json");
            if (!metadata.exists()) continue;
            try { JSONObject o = new JSONObject(new String(Files.readAllBytes(metadata.toPath()), StandardCharsets.UTF_8));
                long bytes = 0; File[] children = d.listFiles(); if (children != null) for (File f : children) bytes += f.length();
                o.put("bytes", bytes); result.put(o);
            } catch (Exception ignored) { }
        }
        return result;
    }
    static File validatedDirectory(File root, String id) throws IOException {
        if (!id.matches("[A-Za-z0-9_-]+")) throw new IOException("无效记录编号");
        File d = new File(root,id);
        if (!d.isDirectory() || !d.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())) throw new IOException("记录不存在");
        return d;
    }
    static JSONObject review(File root, String id) throws Exception {
        File d = validatedDirectory(root,id);
        JSONObject metadata = new JSONObject(new String(Files.readAllBytes(new File(d,"manifest.json").toPath()),StandardCharsets.UTF_8));
        Map<String,Long> counts = new HashMap<>(); JSONArray stats = metadata.optJSONArray("statistics");
        if (stats != null) for (int i=0;i<stats.length();i++) { JSONObject s=stats.getJSONObject(i);counts.put(s.getString("id"),s.optLong("count")); }
        Map<String,JSONArray> series = new LinkedHashMap<>(); Map<String,Long> seen=new HashMap<>();
        JSONArray route=new JSONArray(), markers=new JSONArray(), states=new JSONArray(); ArrayDeque<JSONObject> raw=new ArrayDeque<>();
        long locationSeen=0;
        JSONObject eventCounts=metadata.optJSONObject("eventCounts");
        long locationCount=eventCounts==null?0:eventCounts.optLong("location");
        int routeStride=(int)Math.max(1,Math.ceil(locationCount/2000.0));
        try (BufferedReader reader=Files.newBufferedReader(new File(d,"samples.jsonl").toPath(),StandardCharsets.UTF_8)) {
            String line;
            while((line=reader.readLine())!=null) {
                JSONObject e; try {e=new JSONObject(line);} catch(Exception ignored){continue;}
                if(raw.size()==80)raw.removeFirst();raw.add(e);
                switch(e.optString("kind")) {
                    case "sensor" -> {
                        String sid=e.getString("id");long n=seen.getOrDefault(sid,0L);seen.put(sid,n+1);
                        long stride=Math.max(1,(long)Math.ceil(counts.getOrDefault(sid,1L)/600.0));
                        JSONArray points=series.computeIfAbsent(sid,k->new JSONArray());
                        if(n%stride==0 && points.length()<600)points.put(new JSONObject().put("s",e.optDouble("s")).put("values",e.getJSONArray("values")));
                    }
                    case "location" -> {if(locationSeen++%routeStride==0 && route.length()<2000)route.put(e);}
                    case "marker" -> {if(markers.length()<5000)markers.put(e);}
                    case "state" -> {if(states.length()<5000)states.put(e);}
                }
            }
        }
        JSONObject streams=new JSONObject();for(var entry:series.entrySet())streams.put(entry.getKey(),entry.getValue());
        return new JSONObject().put("manifest",metadata).put("streams",streams).put("route",route).put("markers",markers).put("states",states).put("raw",new JSONArray(raw));
    }
    static JSONObject page(File root,String id,long offset,String filter) throws Exception {
        File d=validatedDirectory(root,id);JSONArray events=new JSONArray();long matched=0;boolean more=false;
        try(BufferedReader reader=Files.newBufferedReader(new File(d,"samples.jsonl").toPath(),StandardCharsets.UTF_8)) {
            String line;
            while((line=reader.readLine())!=null) {
                JSONObject e;try{e=new JSONObject(line);}catch(Exception ignored){continue;}
                if(!filter.isEmpty()&&!filter.equals(e.optString("id"))&&!filter.equals(e.optString("kind")))continue;
                if(matched++<Math.max(0,offset))continue;
                if(events.length()==100){more=true;break;}events.put(e);
            }
        }
        return new JSONObject().put("rows",events).put("offset",offset).put("hasMore",more);
    }
    static void export(File root,String id,OutputStream output) throws Exception {
        File d=validatedDirectory(root,id);
        try(ZipOutputStream zip=new ZipOutputStream(output,StandardCharsets.UTF_8)) {
            File[] files=d.listFiles(); if(files==null)throw new IOException("记录为空");Arrays.sort(files,Comparator.comparing(File::getName));
            byte[] buffer=new byte[65536];
            for(File f:files) {if(!f.isFile()||f.getName().endsWith(".tmp"))continue;zip.putNextEntry(new ZipEntry(id+"/"+f.getName()));
                try(InputStream in=new FileInputStream(f)){int n;while((n=in.read(buffer))!=-1)zip.write(buffer,0,n);}zip.closeEntry();}
        }
    }
}
