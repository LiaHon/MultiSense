/* Browser-only simulator. This file is deliberately NOT packaged in the Android APK. */
'use strict';
(()=>{
const definitions=[[1,'加速度','m/s²'],[4,'角速度','rad/s'],[2,'磁场','µT'],[9,'重力','m/s²'],[10,'线性加速度','m/s²'],[11,'旋转向量','原生值'],[5,'光照','lx'],[6,'气压','hPa'],[8,'接近距离','cm'],[13,'环境温度','°C'],[12,'相对湿度','%'],[19,'累计步数','步（设备累计）']];
const inventory=definitions.map(([type,title,unit],i)=>({id:'s'+String(i).padStart(3,'0'),type,title,unit,name:title+' · 浏览器模拟',vendor:'MultiSense UI Preview',reportingMode:[5,6,8,13,12,19].includes(type)?1:0,wakeUp:false,range:type===1?39.2:1000,resolution:.001,powerMa:.5}));
let state={native:false,device:'界面预览 · 模拟设备',inventory,recording:false,busy:false,rows:0,duration:0,latest:{},streams:{},stats:[],raw:[],route:[],markers:[],states:[],satellites:[],scene:'室内',action:'步行',label:'步行',warnings:[],networkCounts:{},networkStatus:{}},record=[],sessions=[],timer,started=0,wall=0,options={},lastEnv=-1,lastLocation=-1,lastNetwork=-1;
const clone=v=>JSON.parse(JSON.stringify(v)), json=()=>JSON.stringify(state);
function event(kind,s,extra={}){return{kind,t:Math.round((started+s*1000)*1e6),s,wall:Math.round(wall+s*1000),scene:state.scene,action:state.action,label:state.label,...extra};}
function append(e){record.push(e);state.rows++;state.raw.push(e);if(state.raw.length>80)state.raw.shift();}
function sample(src,s,v){let stat=state.stats.find(x=>x.id===src.id);if(!stat){stat={id:src.id,count:0,hz:0,maxGapMs:0,nonMonotonic:0,meanV0:0,varianceV0:0,_first:s,_last:s,_m2:0};state.stats.push(stat);}if(stat.count)stat.maxGapMs=Math.max(stat.maxGapMs,(s-stat._last)*1000);stat._last=s;stat.count++;stat.hz=stat.count>1?(stat.count-1)/(s-stat._first):0;const delta=v[0]-stat.meanV0;stat.meanV0+=delta/stat.count;stat._m2+=delta*(v[0]-stat.meanV0);stat.varianceV0=stat.count>1?stat._m2/(stat.count-1):0;state.latest[src.id]={values:v,ageMs:0};const series=state.streams[src.id]||(state.streams[src.id]=[]);if(!series.length||s-series.at(-1).s>=.04){series.push({s,values:v});if(series.length>300)series.shift();}append(event('sensor',s,{id:src.id,type:src.type,accuracy:3,values:v}));}
function tick(){
  const s=(performance.now()-started)/1000;
  state.duration=s;
  const p=2*Math.PI*1.8*s,j=.018*Math.sin(s*37.4),isStatic=state.action==='静止'||state.action==='停步'||state.label==='静止'||state.label==='停步',amp=isStatic?.01:1;
  for(const src of inventory){
    if(!src._selected)continue;
    const period=src.reportingMode!==0?1:[2].includes(src.type)?.02:[11].includes(src.type)?.01:1/(options.hz||200);
    if(s-(src._lastSample??-1)<period)continue;
    src._lastSample=s;
    let v;
    switch(src.type){
      case 1:v=[amp*.32*Math.sin(p)+j,amp*.55*Math.cos(p+.3)+j,9.81+amp*1.15*Math.sin(p)+j];break;
      case 4:v=[amp*.09*Math.sin(p+.8),amp*.16*Math.cos(p),amp*.04*Math.sin(p*.4)];break;
      case 2:v=[18+Math.sin(s*.1),-6+Math.cos(s*.1),39+Math.sin(s*.2)];break;
      case 9:v=[.1,.4,9.8];break;
      case 10:v=[amp*.32*Math.sin(p),amp*.55*Math.cos(p+.3),amp*1.15*Math.sin(p)];break;
      case 11:v=[0,0,Math.sin(-(.7+s*.012)/2),Math.cos(-(.7+s*.012)/2)];break;
      default:if(Math.floor(s)===lastEnv)continue;v=[{5:285+8*Math.sin(s*.1),6:1012.4+.2*Math.sin(s*.1),8:5,13:23.8,12:42,19:Math.floor(s*1.8)}[src.type]];
    }
    sample(src,s,v);
  }
  lastEnv=Math.floor(s);
  if(Math.floor(s)!==lastLocation&&options.gnss){
    lastLocation=Math.floor(s);
    const l=event('location',s,{provider:'gps',lat:39.9607+s*.000005,lon:116.3572+s*.000007,accuracy:4.2+Math.sin(s)*.2,speed:1.25,alt:49.8});
    state.location=l;
    state.route.push(l);
    append(l);
    state.satellites=Array.from({length:12},(_,i)=>({svid:i+3,constellation:i<7?1:5,cn0:25+20*Math.abs(Math.sin(i+1)),elevation:15+i*5,azimuth:i*28,used:i<8}));
    append(event('satellites',s,{satellites:clone(state.satellites)}));
  }
  for(const stat of state.stats)stat.sessionHz=s>0?stat.count/s:0;
  if(Math.floor(s)!==lastNetwork){
    lastNetwork=Math.floor(s);
    for(const k of ['wifi','ble','cell']){
      if(options[k]!==false&&(k==='ble'||lastNetwork%30===0)){
        const e=event('network',s,{source:k,address:k==='wifi'?'02:00:00:00:00:01':k==='ble'?'02:00:00:00:00:02':'SIMULATED_CELL',name:'模拟设备',rssiDbm:-55,frequencyMhz:k==='wifi'?2412:undefined});
        append(e);
        state.networkCounts[k]=(state.networkCounts[k]||0)+1;
        state.networkStatus[k]='模拟观测 · 无硬件访问';
      }
    }
  }
  if(record.length>120000)stop();
}
function start(jsonOptions){
  if(state.recording)return;
  options=JSON.parse(jsonOptions);
  started=performance.now();
  wall=Date.now();
  record=[];
  lastEnv=-1;
  lastLocation=-1;
  lastNetwork=-1;
  const initialScene=(options.scene||'室内').trim()||'室内';
  const initialAction=(options.action||options.initialState||'步行').trim()||'步行';
  const initialLabel=initialAction;
  state={
    native:false,
    device:'界面预览 · 模拟设备',
    inventory,
    recording:true,
    busy:false,
    rows:0,
    duration:0,
    latest:{},
    streams:{},
    stats:[],
    raw:[],
    route:[],
    markers:[],
    states:[],
    satellites:[],
    scene:initialScene,
    action:initialAction,
    label:initialLabel,
    initialState:initialAction,
    networkCounts:{},
    networkStatus:Object.fromEntries(['wifi','ble','cell'].map(k=>[k,options[k]===false?'未选择':'模拟扫描待开始'])),
    warnings:[],
    requestedHz:options.hz||200,
    sessionId:'demo_'+wall
  };
  const initialEvent=event('state',0,{scene:initialScene,action:initialAction,label:initialLabel});
  state.states.push(initialEvent);
  append(initialEvent);
  const allowed=new Set(options.sensorIds);
  for(const src of inventory){
    src._selected=allowed.has(src.id);
    src._lastSample=-1;
    src.state=src._selected?'采集中':'未选择';
  }
  timer=setInterval(tick,1000/(options.hz||200));
}
function stop(){
  if(!state.recording)return;
  clearInterval(timer);
  state.duration=(performance.now()-started)/1000;
  state.recording=false;
  for(const stat of state.stats)stat.sessionHz=state.duration>0?stat.count/state.duration:0;
  state.savedId=state.sessionId;
  const manifest={
    id:state.savedId,
    title:options.title,
    scene:state.scene,
    action:state.action,
    defaultScene:options.scene||state.scene,
    defaultAction:options.action||state.action,
    pose:options.pose,
    startedWallMs:wall,
    durationS:state.duration,
    rows:state.rows,
    status:'saved',
    demo:true,
    requestedHz:options.hz||200,
    initialState:options.action||options.initialState||'步行',
    networkStatus:clone(state.networkStatus),
    networkCounts:clone(state.networkCounts),
    sensors:clone(inventory),
    statistics:clone(state.stats),
    bytes:JSON.stringify(record).length
  };
  sessions.unshift({
    manifest,
    events:clone(record),
    streams:clone(state.streams),
    route:clone(state.route),
    markers:clone(state.markers),
    states:clone(state.states),
    raw:clone(state.raw)
  });
}
function switchState(jsonState){
  if(!state.recording)return;
  const s=JSON.parse(jsonState);
  const nextScene=(s.scene||'').trim();
  const nextAction=(s.action||'').trim();
  if(!nextScene||!nextAction)return;
  if(nextScene===state.scene&&nextAction===state.action)return;
  state.scene=nextScene;
  state.action=nextAction;
  state.label=nextAction;
  const nowS=(performance.now()-started)/1000;
  const e=event('state',nowS,{scene:nextScene,action:nextAction,label:nextAction});
  state.states.push(e);
  append(e);
}
function marker(jsonMarker){
  if(!state.recording)return;
  const m=JSON.parse(jsonMarker);
  const e=event('marker',(performance.now()-started)/1000,{...m,changesState:false});
  state.markers.push(e);
  append(e);
}
function zip(files){const encoder=new TextEncoder(),chunks=[],central=[];let offset=0;const crcTable=Array.from({length:256},(_,n)=>{for(let i=0;i<8;i++)n=(n&1)?0xedb88320^(n>>>1):n>>>1;return n>>>0;});const crc=data=>{let c=0xffffffff;for(const b of data)c=crcTable[(c^b)&255]^(c>>>8);return(c^0xffffffff)>>>0;};const block=(size)=>{const b=new Uint8Array(size);return[b,new DataView(b.buffer)];};
for(const[name,text]of Object.entries(files)){const filename=encoder.encode(name),data=encoder.encode(text),sum=crc(data),[head,v]=block(30);v.setUint32(0,0x04034b50,true);v.setUint16(4,20,true);v.setUint16(6,0x800,true);v.setUint32(14,sum,true);v.setUint32(18,data.length,true);v.setUint32(22,data.length,true);v.setUint16(26,filename.length,true);chunks.push(head,filename,data);const[c,cv]=block(46);cv.setUint32(0,0x02014b50,true);cv.setUint16(4,20,true);cv.setUint16(6,20,true);cv.setUint16(8,0x800,true);cv.setUint32(16,sum,true);cv.setUint32(20,data.length,true);cv.setUint32(24,data.length,true);cv.setUint16(28,filename.length,true);cv.setUint32(42,offset,true);central.push(c,filename);offset+=head.length+filename.length+data.length;}
const centralSize=central.reduce((n,b)=>n+b.length,0),[end,ev]=block(22),count=Object.keys(files).length;ev.setUint32(0,0x06054b50,true);ev.setUint16(8,count,true);ev.setUint16(10,count,true);ev.setUint32(12,centralSize,true);ev.setUint16(16,offset,true);return new Blob([...chunks,...central,end],{type:'application/zip'});}
function exportSession(id){
  const s=sessions.find(s=>s.manifest.id===id);
  if(!s)return;
  const cell=v=>{const text=String(v??'');return /[,"\n]/.test(text)?'"'+text.replaceAll('"','""')+'"':text;};
  const csv=rows=>rows.map(r=>r.map(cell).join(',')).join('\n')+'\n';
  const files={};
  files[id+'/manifest.json']=JSON.stringify(s.manifest,null,2);
  files[id+'/samples.jsonl']=s.events.map(e=>JSON.stringify(e)).join('\n')+'\n';
  const sensorRows=s.events.filter(e=>e.kind==='sensor').map(e=>{
    const v=e.values||[];
    const cells=[e.t,e.s,e.wall,e.id,e.type,e.accuracy];
    for(let i=0;i<6;i++)cells.push(i<v.length?v[i]:'');
    cells.push(JSON.stringify(v),e.scene||s.manifest.scene||'',e.action||s.manifest.action||'');
    return cells;
  });
  files[id+'/sensors.csv']=csv([['elapsed_ns','relative_s','wall_ms','sensor_id','sensor_type','accuracy','v0','v1','v2','v3','v4','v5','values_json','scene','action'],...sensorRows]);
  files[id+'/locations.csv']=csv([['elapsed_ns','relative_s','wall_ms','provider','latitude_deg','longitude_deg','altitude_m','horizontal_accuracy_m','vertical_accuracy_m','speed_m_s','speed_accuracy_m_s','bearing_deg','bearing_accuracy_deg'],...s.route.map(e=>[e.t||'',e.s,e.wall||'',e.provider||'simulated',e.lat,e.lon,e.alt||'',e.accuracy||'',e.verticalAccuracy||'',e.speed||'',e.speedAccuracy||'',e.bearing||'',e.bearingAccuracy||''])]);
  files[id+'/satellites.csv']=csv([['elapsed_ns','relative_s','svid','constellation','cn0_dbhz','elevation_deg','azimuth_deg','used_in_fix']]);
  files[id+'/nmea.csv']=csv([['received_elapsed_ns','source_wall_ms','sentence']]);
  files[id+'/markers.csv']=csv([['elapsed_ns','relative_s','label','note','reference_latitude_deg','reference_longitude_deg'],...s.markers.map(e=>[e.t,e.s,e.label,e.note,e.refLat,e.refLon])]);
  files[id+'/states.csv']=csv([['elapsed_ns','relative_s','wall_ms','scene','action'],...(s.states||[]).map(e=>[e.t,e.s,e.wall,e.scene||s.manifest.scene||'',e.action||s.manifest.action||''])]);
  const segments=[];
  let curAction=s.manifest.defaultAction||s.manifest.action||s.manifest.initialState||'静止';
  let segStart=0;
  for(const st of (s.states||[])){
    const nextAction=st.action||curAction;
    const at=st.s||0;
    if(nextAction!==curAction){
      if(at>segStart)segments.push([segStart,at,curAction]);
      curAction=nextAction;
      segStart=at;
    }
  }
  if((s.manifest.durationS||0)>segStart){
    segments.push([segStart,s.manifest.durationS,curAction]);
  }
  files[id+'/labels.csv']=csv(segments);
  files[id+'/network.csv']=csv([['elapsed_ns','relative_s','wall_ms','source','address','name','rssi_dbm','frequency_mhz','details_json'],...s.events.filter(e=>e.kind==='network').map(e=>[e.t,e.s,e.wall,e.source,e.address,e.name,e.rssiDbm,e.frequencyMhz,JSON.stringify(e)])]);
  const stateNames=new Set(segments.map(v=>v[2]));
  const durationS=s.manifest.durationS||0;
  files[id+'/collection_check.json']=JSON.stringify({
    scope:'采集检查；不含 FFT、分类器或完整作业 self_test',
    durationS,
    durationPass:durationS>=1200,
    stateCount:stateNames.size,
    statesPass:stateNames.size>=3,
    finishedNormally:s.manifest.status==='saved',
    demo:true,
    note:'模拟界面数据，不作为真实采集验收'
  },null,2);
  files[id+'/data_spec.md']=`# 采集数据规格 (模拟记录)\n\n设备：${s.manifest.device||'界面预览 · 模拟设备'}\n场景：${s.manifest.scene||'默认场景'}；携带位置：${s.manifest.pose||'手持固定'}\n时长：${durationS.toFixed(3)} 秒；结束状态：${s.manifest.status}；动作状态种类：${stateNames.size}\n\n模拟界面数据，不作为真实采集验收。\n`;
  const url=URL.createObjectURL(zip(files)),a=document.createElement('a');
  a.href=url;
  a.download='MultiSense_'+id+'_SIMULATED.zip';
  a.click();
  setTimeout(()=>URL.revokeObjectURL(url),5000);
  window.nativeMessage('已导出模拟记录 ZIP；真机记录请使用 APK');
}
window.PreviewBackend={snapshot:json,start,stop,switchState,marker,networkSettings:()=>window.nativeMessage('网页模拟器不访问无线硬件'),sessions:()=>JSON.stringify(sessions.map(s=>s.manifest)),review:id=>JSON.stringify(sessions.find(s=>s.manifest.id===id)||{error:'记录不存在'}),page:(id,offset,filter)=>{const s=sessions.find(s=>s.manifest.id===id),all=(s?.events||[]).filter(e=>!filter||e.kind===filter||e.id===filter);return JSON.stringify({rows:all.slice(offset,offset+100),offset,hasMore:all.length>offset+100});},exportSession,locationSettings:()=>window.nativeMessage('网页预览不访问设备定位；请在 Android 真机上操作')};
})();
