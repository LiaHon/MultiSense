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
        recorder.write(new JSONObject().put("kind","marker").put("t",41_000_000_000L).put("s",40).put("label","快走,一").put("note",note).put("refLat",39.9).put("refLon",116.3));
        recorder.write(new JSONObject().put("kind","location").put("t",41_000_000_001L).put("s",40).put("wall",140000).put("provider","gps").put("lat",39.9).put("lon",116.3).put("accuracy",4));
        JSONArray statistics=new JSONArray().put(new JSONObject().put("id","s000").put("count",2000).put("hz",stats.hz()).put("maxGapMs",stats.maxGapNs/1e6));
        recorder.finish(statistics,40,"saved");String id=recorder.directory.getName();
        check(recorder.rows==2002,"event count");check(Math.abs(stats.hz()-50)<1e-9,"actual Hz");check(stats.maxGapNs==20_000_000L,"gap");
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
        check(entries==7&&events==2002,"ZIP retains every event and all files");
        String csv=Files.readString(new File(recorder.directory,"markers.csv").toPath());check(csv.contains("\"快走,一\"")&&csv.contains("引用\"\""),"CSV escaping");
        Files.writeString(new File(recorder.directory,"samples.jsonl").toPath(),"{\"truncated\":",StandardOpenOption.APPEND);
        check(SessionRecorder.review(root,id).getJSONArray("markers").length()==1,"partial trailing line recoverable");
        System.out.println("PASS: storage round-trip, full vectors, timestamps, statistics, pagination, CSV escaping, ZIP completeness and interrupted-file recovery");
    }
}
