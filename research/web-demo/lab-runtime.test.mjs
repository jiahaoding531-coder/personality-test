import {fileURLToPath} from 'node:url';
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const runtime=await import('./lab-runtime.mjs').catch(e=>{if(e.code==='ERR_MODULE_NOT_FOUND')return {};throw e;});
const root=fileURLToPath(new URL('./fixtures/baseline/',import.meta.url));
const base=JSON.parse(fs.readFileSync(root+'input.json'));
const run=input=>{assert.equal(typeof runtime.executeJavaExperiment,'function','缺少独立Java运行器');return runtime.executeJavaExperiment(input);};
test('真实Java执行与既有原输入报告完全一致',{timeout:20000},async()=>{const r=await run(base);assert.deepEqual(r.report,JSON.parse(fs.readFileSync(root+'report.json')));assert.equal(r.input.datasetKind,'SYNTHETIC_TEST_ONLY');assert.match(r.trace.reportSha256,/^[a-f0-9]{64}$/);});
test('真实Java对距离与时间约束生效，不凑Top3',{timeout:20000},async()=>{
 const input=structuredClone(base);input.queries=input.queries.map(q=>({...q,maxDistanceKm:.5,remainingMinutes:20}));const r=await run(input);
 for(const v of r.report.variants){assert.equal(v.aggregate.emptyQueries,18);assert.ok(v.decisions.every(d=>d.selected.length===0));}
});
test('相同请求运行两次的报告与输入摘要一致，运行目录独立',{timeout:20000},async()=>{const a=await run(base),b=await run(base);assert.notEqual(a.trace.runId,b.trace.runId);assert.equal(a.trace.reportSha256,b.trace.reportSha256);assert.equal(a.trace.inputSha256,b.trace.inputSha256);});
test('真实Java严格保留半公里距离，三组均不放大召回范围',{timeout:20000},async()=>{const input=structuredClone(base);input.queries=input.queries.map(q=>({...q,maxDistanceKm:.5,remainingMinutes:120}));const r=await run(input);for(const v of r.report.variants)for(const d of v.decisions){assert.ok(d.eligible.every(p=>p.distanceKm<=.5));assert.ok(d.selected.length<=3);assert.ok(d.selected.every(id=>d.eligible.some(p=>p.id===id)));}});
test('本工具缓存字节码变化时拒绝运行，恢复后校验正常',{timeout:20000},async()=>{
 const cache=new URL('./lab-cache/',import.meta.url),current=JSON.parse(fs.readFileSync(new URL('current.json',cache))),build=new URL(`builds/${current.buildId}/`,cache),meta=JSON.parse(fs.readFileSync(new URL('build.json',build))),name=Object.keys(meta.runtimeClassSha256)[0],file=new URL('classes/'+name,build),before=fs.readFileSync(file);
 try{fs.writeFileSync(file,Buffer.concat([before,Buffer.from('TEST_ONLY')]));await assert.rejects(()=>run(base),/字节码|变化/);}finally{fs.writeFileSync(file,before);}
 const r=await run(base);assert.equal(r.report.datasetKind,'SYNTHETIC_TEST_ONLY');
});
test('环境自检只读取构建信息，不创建新的实验运行目录',async()=>{
 assert.equal(typeof runtime.inspectLabEnvironment,'function','缺少本机环境自检');
 const before=fs.readdirSync(new URL('./lab-runs/',import.meta.url)).sort();
 const info=await runtime.inspectLabEnvironment();assert.equal(info.classCount,30);assert.equal(info.nodeCount,6);assert.equal(info.queryCount,18);assert.equal(info.datasetKind,'SYNTHETIC_TEST_ONLY');
 assert.deepEqual(fs.readdirSync(new URL('./lab-runs/',import.meta.url)).sort(),before);
});
