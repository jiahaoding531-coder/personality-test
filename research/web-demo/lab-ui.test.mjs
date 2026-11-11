import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../../frontend/package.json',import.meta.url));
const {JSDOM,VirtualConsole}=require('jsdom');
const html=fs.readFileSync(new URL('./index.html',import.meta.url),'utf8');
const data=JSON.parse(html.match(/<script id="demo-data" type="application\/json">([\s\S]*?)<\/script>/)[1]);
function bundle(options={maxDistanceKm:2,remainingMinutes:120,fairnessWeight:.35,seed:20261026}){
 const input=structuredClone(data.input);Object.assign(input,{seed:options.seed,fairnessWeight:options.fairnessWeight});input.queries=input.queries.map(q=>({...q,maxDistanceKm:options.maxDistanceKm,remainingMinutes:options.remainingMinutes}));
 return {input,report:structuredClone(data.report),trace:{runId:'TEST_ONLY',inputSha256:'a'.repeat(64),reportSha256:'b'.repeat(64)}};
}
async function run(check,{url='http://127.0.0.1:5186/',fetch=async()=>({ok:true,json:async()=>bundle()}),setupWindow=()=>{}}={}){
 const errors=[],console=new VirtualConsole();console.on('jsdomError',e=>errors.push(e.message));
 const dom=new JSDOM(html,{runScripts:'dangerously',url,virtualConsole:console,beforeParse(w){w.fetch=fetch;setupWindow(w);}}),d=dom.window.document;
 assert.ok(d.getElementById('lab-run'),'尚未加入实时实验运行入口');
 const flush=()=>new Promise(r=>setImmediate(r));
 try{await check({d,dom,flush});assert.deepEqual(errors,[]);}finally{dom.window.close();}
}
test('点击后请求本机接口，展示三策略和本轮实际条件',()=>run(async({d,flush})=>{d.getElementById('lab-run').click();await flush();assert.equal(d.getElementById('lab-result').hidden,false);assert.equal(d.querySelectorAll('#lab-result .strategy').length,3);assert.match(d.getElementById('lab-context').textContent,/2 公里.*120 分钟/);}));
test('连续快速点击只提交一次，不排队或自动重试',()=>{
 let calls=0,resolve;return run(async({d,flush})=>{d.getElementById('lab-run').click();d.getElementById('lab-run').click();await flush();assert.equal(calls,1);resolve({ok:true,json:async()=>bundle()});await flush();},{fetch:()=>{calls++;return new Promise(r=>resolve=r);}});
});
test('修改条件后旧响应失效，不把旧报告当新条件结果',()=>{
 let resolve;return run(async({d,dom,flush})=>{d.getElementById('lab-run').click();const distance=d.getElementById('lab-distance');distance.value='.5';distance.dispatchEvent(new dom.window.Event('input',{bubbles:true}));resolve({ok:true,json:async()=>bundle()});await flush();assert.equal(d.getElementById('lab-result').hidden,true);assert.match(d.getElementById('lab-status').textContent,/条件已变化/);},{fetch:()=>new Promise(r=>resolve=r)});
});
test('失败时清空旧结果并提示，错误内容不直接回显',()=>{
 let calls=0;return run(async({d,flush})=>{d.getElementById('lab-run').click();await flush();d.getElementById('lab-run').click();await flush();assert.equal(d.getElementById('lab-result').hidden,true);assert.equal(d.getElementById('lab-error').hidden,false);assert.doesNotMatch(d.getElementById('lab-error').textContent,/private/);},{fetch:async()=>{if(calls++===0)return{ok:true,json:async()=>bundle()};throw Error('private');}});
});
test('服务器返回不匹配条件时拒绝，不制造新指标',()=>run(async({d,flush})=>{d.getElementById('lab-run').click();await flush();assert.equal(d.getElementById('lab-result').hidden,true);assert.equal(d.getElementById('lab-error').hidden,false);},{fetch:async()=>{const b=bundle();b.report.seed=999;return{ok:true,json:async()=>b};}}));
test('单文件离线打开不调用接口，旧报告与资料查看仍可用',()=>{
 let calls=0;return run(async({d})=>{assert.equal(d.getElementById('lab-run').disabled,true);assert.match(d.getElementById('lab-status').textContent,/本机服务/);d.getElementById('tab-evidence').click();assert.equal(d.getElementById('evidence-panel').hidden,false);assert.equal(calls,0);},{url:'file:///demo.html',fetch:async()=>{calls++;throw Error();}});
});
test('运行与切换回放轮次不改变预先保存的实验或采集资料',()=>run(async({d,flush})=>{const old=d.getElementById('strategies').innerHTML,ev=d.getElementById('evidence').innerHTML;d.getElementById('lab-run').click();await flush();d.getElementById('lab-query').value=data.report.queryOrder[1];d.getElementById('lab-query').dispatchEvent(new d.defaultView.Event('change'));assert.equal(d.getElementById('strategies').innerHTML,old);assert.equal(d.getElementById('evidence').innerHTML,ev);}));

for(const phase of ['请求','JSON读取'])test(`${phase}持续挂起时15秒截止恢复按钮，晚到结果失效且不自动重试`,()=>{
 const timers=new Map();let resolve,calls=0,signal;
 const setupWindow=w=>{const native=w.setTimeout.bind(w),cancel=w.clearTimeout.bind(w);w.setTimeout=(fn,ms)=>{if(ms===15000){timers.set(700,fn);return 700;}return native(fn,ms);};w.clearTimeout=id=>{timers.delete(id);cancel(id);};};
 const fetch=(_url,options)=>{calls++;signal=options.signal;if(phase==='请求')return new Promise(r=>resolve=r);return Promise.resolve({ok:true,json:()=>new Promise(r=>resolve=r)});};
 return run(async({d,flush})=>{
  d.getElementById('lab-run').click();await flush();assert.equal(timers.size,1,'没有15秒截止时间');
  timers.get(700)();await flush();assert.equal(d.getElementById('lab-run').disabled,false);assert.match(d.getElementById('lab-error').textContent,/超时/);assert.equal(signal.aborted,true);assert.equal(calls,1);
  resolve(phase==='请求'?{ok:true,json:async()=>bundle()}:bundle());await flush();assert.equal(d.getElementById('lab-result').hidden,true);assert.equal(d.getElementById('lab-download').disabled,true);assert.equal(timers.size,0);
 },{fetch,setupWindow});
});
test('新运行失败后清除旧轮次选择，不保留失效回放入口',()=>{
 let calls=0;return run(async({d,flush})=>{d.getElementById('lab-run').click();await flush();assert.equal(d.getElementById('lab-query').options.length,18);d.getElementById('lab-run').click();await flush();assert.equal(d.getElementById('lab-query').options.length,0);},{fetch:async()=>{if(calls++===0)return{ok:true,json:async()=>bundle()};throw Error('TEST_ONLY');}});
});
