// Isolated source-level adapter checks. No browser, microphone, service or provider is contacted.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../../../../main/java/com/example/voice/VoiceRoomAdapter.kt'), 'utf8');
const bootstrap = source.match(/val BOOTSTRAP = """([\s\S]*?)"""\.trimIndent\(\)/)[1];
const tests = [];
function test(name, run) { tests.push({name, run}); }
function fixture() {
  let data = {ok:true, audioQueue:[{id:'historic', seq:1, status:'queued'}],
    events:[{type:'speak.error', error:{status:429}}, {type:'transcript.candidate',text:'uncertain',diagnostics:{reason:'confidence'}}],
    candidates:[{utteranceId:'held'}], ttsPool:{selectedAccount:'existing'}};
  const stats = {start:0,stop:0,capture:0,discard:0,nativeStart:0,nativeStop:0,status:[]};
  const listeners = {};
  const intervals = [];
  let running = false, ready = false, blockBaseline;
  const context = {
    URL, Headers, Response, Promise, Set, Error, JSON,
    location:{href:'http://127.0.0.1:8790/',origin:'http://127.0.0.1:8790'},
    document:{visibilityState:'visible',addEventListener(name, fn){listeners[name]=fn;}},
    navigator:{mediaDevices:{getUserMedia:async()=>{stats.capture++;return {};}}},
    fetch:async()=>{if(blockBaseline) await blockBaseline;return new Response(JSON.stringify(data),{status:200});},
    setInterval(fn){intervals.push(fn);return intervals.length;},
    VoiceConsoleBridge:{start(){stats.nativeStart++;},stop(){stats.nativeStop++;},status(json){stats.status.push(JSON.parse(json));},error(e){throw Error(e);}},
  };
  vm.createContext(context);
  vm.runInContext(bootstrap, context);
  context.voiceLab = {
    status(){return {running,starting:false,micOpen:running,serviceConnected:true};},
    async start(){stats.start++;await context.navigator.mediaDevices.getUserMedia({audio:true});running=true;},
    async stop(){stats.stop++;running=false;},
    discardPlaybackQueue(){stats.discard++;}
  };
  return {context, stats, listeners, intervals,
    setData(value){data=value;}, getData(){return data;},
    baselineWait(promise){blockBaseline=promise;},
    async poll(){return JSON.parse(await (await context.fetch('/api/state')).text());}};
}
test('composition, ready and status polling never request microphone or playback', async()=>{
  const f=fixture(); f.context.voiceConsoleAdapter.ready();
  for(const tick of f.intervals) tick();
  assert.equal(f.stats.start,0); assert.equal(f.stats.capture,0);
  const data=await f.poll(); assert.equal(data.audioQueue.length,0);
  assert.deepEqual(data.events,f.getData().events); assert.deepEqual(data.candidates,f.getData().candidates);
  assert.deepEqual(data.ttsPool,f.getData().ttsPool);
});
test('unarmed getUserMedia is rejected and embedded Start only requests native action', async()=>{
  const f=fixture(); f.context.voiceConsoleAdapter.ready();
  await assert.rejects(f.context.navigator.mediaDevices.getUserMedia({audio:true}), /Choose Start/);
  let stopped=false;
  f.listeners.click({target:{closest:()=>({id:'start'})},preventDefault(){},stopImmediatePropagation(){stopped=true;}});
  assert.equal(f.stats.nativeStart,1);assert.equal(f.stats.capture,0);assert.equal(stopped,true);
});
test('explicit Start baselines history; only new audio is eligible; Stop drops local pending playback', async()=>{
  const f=fixture();f.context.voiceConsoleAdapter.ready();
  await f.context.voiceConsoleAdapter.start();assert.equal(f.stats.capture,1);
  f.setData({...f.getData(), audioQueue:[...f.getData().audioQueue,{id:'new',seq:2,status:'queued'}]});
  const data=await f.poll();assert.deepEqual(data.audioQueue.map(a=>a.id),['new']);
  await f.context.voiceConsoleAdapter.stop();assert.equal(f.stats.stop,1);assert.equal(f.stats.discard,2);
  await f.context.voiceConsoleAdapter.start();assert.equal((await f.poll()).audioQueue.length,0);
});
test('suspend and visibility resume no capture and pause room intervals', async()=>{
  const f=fixture();f.context.voiceConsoleAdapter.ready();
  let polls=0;f.context.setInterval(()=>polls++,500);
  f.listeners.visibilitychange(); // visible is a no-op
  f.context.document.visibilityState='hidden';f.listeners.visibilitychange();
  await Promise.resolve();for(const tick of f.intervals) tick();assert.equal(polls,0);
  f.context.voiceConsoleAdapter.resume();for(const tick of f.intervals) tick();
  assert.equal(polls,1);assert.equal(f.stats.capture,0);assert.equal(f.stats.start,0);
});
test('Stop cancels an explicit Start whose baseline request is still pending', async()=>{
  const f=fixture();f.context.voiceConsoleAdapter.ready();
  let release;f.baselineWait(new Promise(resolve=>release=resolve));
  const start=f.context.voiceConsoleAdapter.start();await f.context.voiceConsoleAdapter.stop();release();await start;
  assert.equal(f.stats.capture,0);assert.equal(f.stats.start,0);
});
test('service baseline failure stays explicit and never falls back to capture', async()=>{
  const f=fixture();f.context.voiceConsoleAdapter.ready();f.setData({ok:false,error:'unavailable'});
  await assert.rejects(f.context.voiceConsoleAdapter.start(), /Voice state HTTP/);
  assert.equal(f.stats.capture,0);
});
(async()=>{for(const t of tests){await t.run();console.log('PASS '+t.name);}console.log(`${tests.length} isolated adapter checks passed`);})().catch(error=>{console.error(error);process.exitCode=1;});
