package edu.multisense.collector;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class StorageTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        File root=Files.createTempDirectory("multisense-test-").toFile();
        JSONObject options=new JSONObject().put("title","存储验证").put("scene","操场").put("pose","手持固定").put("hz",50);
        JSONArray inventory=new JSONArray().put(new JSONObject().put("id","s000").put("type",28));
        long baseNs=9_007_199_254_740_997L;
        SessionRecorder recorder=new SessionRecorder(root,options,inventory,baseNs,100000L);
        SampleStats stats=new SampleStats();
        JSONArray vector=new JSONArray();for(int i=0;i<16;i++)vector.put(i+.123456789);
        for(int i=0;i<2000;i++) {
            long t=baseNs+i*20_000_000L;
            recorder.write(new JSONObject().put("kind","sensor").put("t",Long.toString(t)).put("s",i*.02).put("wall",100000L+i*20L).put("id","s000").put("type",28).put("accuracy",3).put("values",vector));
            stats.add(t,new float[]{i%2==0?1:3});
        }
        String note="引用\"与逗号,\n换行";
        recorder.write(new JSONObject().put("kind","marker").put("t",41_000_000_000L).put("s",10).put("label","快走,一").put("note",note).put("refLat",39.9).put("refLon",116.3));
        recorder.write(new JSONObject().put("kind","marker").put("t",41_000_000_001L).put("s",15).put("label","进入楼宇").put("changesState",false));
        recorder.write(new JSONObject().put("kind","marker").put("t",41_000_000_002L).put("s",20).put("label","电梯").put("changesState",true));
        recorder.write(new JSONObject().put("kind","marker").put("t",41_000_000_003L).put("s",22).put("label","电梯").put("changesState",true));
        recorder.write(new JSONObject().put("kind","network").put("t",Long.toString(baseNs+1)).put("s",.001).put("wall",100001).put("source","wifi").put("address","aa:bb:cc:dd:ee:ff").put("name","AP,一").put("rssiDbm",-63).put("frequencyMhz",2412));
        recorder.write(new JSONObject().put("kind","location").put("t",41_000_000_001L).put("s",40).put("wall",140000).put("provider","gps").put("lat",39.9).put("lon",116.3).put("accuracy",4));
        JSONArray statistics=new JSONArray().put(new JSONObject().put("id","s000").put("count",2000).put("hz",stats.hz()).put("maxGapMs",stats.maxGapNs/1e6));
        recorder.finish(statistics,40,"saved");String id=recorder.directory.getName();
        check(recorder.rows==2006,"event count");check(Math.abs(stats.hz()-50)<1e-9,"actual Hz");check(stats.maxGapNs==20_000_000L,"gap");
        stats.add(baseNs+1999*20_000_000L,new float[]{Float.NaN});check(stats.nonMonotonic==1,"duplicate timestamp detected");check(stats.validCount==2000,"missing values excluded");
        JSONObject review=SessionRecorder.review(root,id);check(review.getJSONObject("streams").getJSONArray("s000").length()<=600,"bounded history chart");
        check(review.getJSONArray("markers").getJSONObject(0).getString("note").equals(note),"marker round trip");
        check(review.getJSONArray("route").length()==1,"GNSS review");
        JSONObject page=SessionRecorder.page(root,id,100,"sensor");check(page.getJSONArray("rows").length()==100&&page.getBoolean("hasMore"),"pagination");
        check(page.getJSONArray("rows").getJSONObject(0).getJSONArray("values").length()==16,"full vector preserved");
        check(page.getJSONArray("rows").getJSONObject(0).getLong("t")==baseNs+100*20_000_000L,"64-bit nanosecond timestamp intact beyond JavaScript safe integers");
        check(SessionRecorder.page(root,id,2000,"sensor").getJSONArray("rows").length()==0,"pagination end");
        check(SessionRecorder.list(root).length()==1,"session discovery");
        try{SessionRecorder.validatedDirectory(root,"../escape");throw new AssertionError("path traversal accepted");}catch(IOException expected){}
        ByteArrayOutputStream zipBytes=new ByteArrayOutputStream();SessionRecorder.export(root,id,zipBytes);int entries=0,events=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(zipBytes.toByteArray()),StandardCharsets.UTF_8)){
            ZipEntry entry;while((entry=zip.getNextEntry())!=null){entries++;byte[] bytes=zip.readAllBytes();if(entry.getName().endsWith("samples.jsonl"))events=new String(bytes,StandardCharsets.UTF_8).split("\n").length;}
        }
        check(entries==11&&events==2006,"ZIP retains every event and all files");
        String labels=Files.readString(new File(recorder.directory,"labels.csv").toPath());
        check(labels.equals("0.0,10.0,静止\n10.0,20.0,\"快走,一\"\n20.0,40.0,电梯\n"),"state intervals cover complete session; events and repeated states do not split labels");
        JSONObject quality=new JSONObject(Files.readString(new File(recorder.directory,"collection_check.json").toPath()));
        check(quality.getBoolean("statesPass")&&!quality.getBoolean("durationPass")&&!quality.getBoolean("motionRatePass"),"quality never claims short or missing IMU session passes");
        check(Files.readString(new File(recorder.directory,"network.csv").toPath()).contains("\"AP,一\",-63,2412"),"network observations exported separately with CSV escaping");
        check(Files.readString(new File(recorder.directory,"data_spec.md").toPath()).contains("50.000"),"report uses count divided by full session duration");
        check(SamplingPolicy.motionHz(50)==200&&SamplingPolicy.motionHz(100)==100,"legacy low rate migrates to assignment range");
        check(SamplingPolicy.periodUs(1,200,0)==5000&&SamplingPolicy.periodUs(4,100,0)==10000&&SamplingPolicy.periodUs(9,200,0)==5000&&SamplingPolicy.periodUs(10,100,0)==10000,"all four motion channels use requested period");
        check(SamplingPolicy.periodUs(2,200,0)==20000&&SamplingPolicy.periodUs(11,200,0)==10000&&SamplingPolicy.periodUs(6,200,0)==200000&&SamplingPolicy.periodUs(19,200,1)==0,"magnetic, rotation, slow and event channels use own policies");
        String csv=Files.readString(new File(recorder.directory,"markers.csv").toPath());check(csv.contains("\"快走,一\"")&&csv.contains("引用\"\""),"CSV escaping");
        Files.writeString(new File(recorder.directory,"samples.jsonl").toPath(),"{\"truncated\":",StandardOpenOption.APPEND);
        check(SessionRecorder.review(root,id).getJSONArray("markers").length()==4,"partial trailing line recoverable");
        // Positive collection check with complete motion channels and honest full-session rate.
        JSONArray imu=new JSONArray(),imuStats=new JSONArray();
        for(int type:new int[]{1,4,9,10}) {imu.put(new JSONObject().put("id","imu"+type).put("type",type));imuStats.put(new JSONObject().put("id","imu"+type).put("count",240000));}
        SessionRecorder full=new SessionRecorder(root,new JSONObject(),imu,baseNs,200000);
        full.write(new JSONObject().put("kind","marker").put("t","1").put("s",300).put("label","常速走"));
        full.write(new JSONObject().put("kind","marker").put("t","2").put("s",900).put("label","电梯"));
        full.finish(imuStats,1200,"saved");
        JSONObject passed=new JSONObject(Files.readString(new File(full.directory,"collection_check.json").toPath()));
        check(passed.getBoolean("motionRatePass")&&passed.getBoolean("durationPass")&&passed.getBoolean("statesPass"),"valid 20 minute, 200 Hz, three state collection checks pass");
        System.out.println("PASS: storage round-trip, full vectors, timestamps, statistics, pagination, CSV escaping, ZIP completeness and interrupted-file recovery");
    }
}
