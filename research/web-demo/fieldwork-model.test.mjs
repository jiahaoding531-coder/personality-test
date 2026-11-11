import {fileURLToPath} from 'node:url';
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {fixture,nodeIds} from './fieldwork-fixture.mjs';
import * as model from './fieldwork-model.mjs';
const parse=x=>{assert.equal(typeof model.parseFieldworkReport,'function','缺少采集结果解析能力');return model.parseFieldworkReport(typeof x==='string'?x:JSON.stringify(x),nodeIds);};
test('解析原工具空表结果，历史材料不冒充新点位',()=>{
  const real=fs.readFileSync(fileURLToPath(new URL('./fixtures/fieldwork-empty-review.json',import.meta.url)),'utf8');
  const v=parse(real);assert.equal(v.counts.materials,10);assert.equal(v.counts.points,0);assert.equal(v.nodes.length,3);
});
test('分节点保留原始观察和零数值，但不提供正式坐标或价格字段',()=>{
  const v=parse(fixture());assert.equal(v.nodes[0].facts[0].numericValue,0);assert.equal(v.nodes[0].points[0].coordinateSystem,'UNKNOWN');
  assert.equal(v.nodes[1].facts.length,0);assert.ok(!JSON.stringify(v).includes('"longitude"'));assert.ok(!JSON.stringify(v).includes('TEST_ONLY.jpg'));
});
for(const flag of ['dataVerifiedByTool','experimentAuthorized'])test(`拒绝伪造${flag}`,()=>{const f=fixture();f[flag]=true;assert.throws(()=>parse(f),/资格|标记/);});
test('拒绝伪造点位已采用及事实已核验',()=>{for(const [k,flag] of [['points','adopted'],['facts','verified']]){const f=fixture();f[k][0][flag]=true;assert.throws(()=>parse(f),/资格|标记/);}});
test('非法JSON、版本或类型均被拒绝',()=>{parse(fixture());assert.throws(()=>parse('{'));for(const [k,v] of [['schemaVersion',2],['artifactType','PILOT_EVIDENCE_REGISTER']]){const f=fixture();f[k]=v;assert.throws(()=>parse(f));}});
test('拒绝超限文本以及超过记录上限',()=>{assert.throws(()=>parse(' '.repeat(1048577)),/过大/);const f=fixture();f.facts=Array(501).fill(f.facts[0]);assert.throws(()=>parse(f),/过多/);});
test('拒绝未知节点和失效材料引用',()=>{for(const [k,v] of [['nodeId','OTHER'],['materialId','MISSING']]){const f=fixture();f.points[0][k]=v;assert.throws(()=>parse(f),/节点|材料/);}});
test('拒绝重复材料或点位编号',()=>{for(const k of ['materials','points']){const f=fixture();f[k].push({...f[k][0]});f.counts[k]++;assert.throws(()=>parse(f),/重复/);}});
test('不信任客户端篡改的计数与输入哈希',()=>{const f=fixture();f.counts.points=5;assert.throws(()=>parse(f),/数量/);f.counts.points=1;f.inputSha256.workbook='bad';assert.throws(()=>parse(f),/校验和/);});
test('全部节点记录独立展示，不重复分配给三个地点',()=>{
  const f=fixture();f.facts.push({...f.facts[0],nodeId:null,scope:'ALL_NODES',workbookRow:'现场事实!36'});f.counts.facts++;
  const v=parse(f);assert.equal(v.globalFacts.length,1);assert.equal(v.nodes.reduce((n,x)=>n+x.facts.length,0),1);
});
test('复核声明仍然不授予资格，未知日期可以为空',()=>{
  const f=fixture();f.reviews=[{nodeId:'YEX-01',topic:'身份与范围',materialReference:'F_TEST',reviewerCode:'TEST',reviewDate:null,status:'材料已复核',reason:null,nextAction:null,workbookRow:'人工复核!8'}];f.counts.reviews=1;
  const v=parse(f);assert.equal(v.nodes[0].reviews[0].status,'材料已复核');assert.equal(v.experimentAuthorized,false);
});
test('坐标和数值的类型与范围不正确时拒绝，不把未知补零',()=>{
  for(const [k,v] of [['latitude',91],['longitude','0'],['accuracyMeters',-1],['coordinateSystem','BD09']]){const f=fixture();f.points[0][k]=v;assert.throws(()=>parse(f));}
  const f=fixture();f.facts[0].numericValue=null;assert.equal(parse(f).nodes[0].facts[0].numericValue,null);
});
test('日期不合法和输入原型污染字段被拒绝',()=>{parse(fixture());const f=fixture();f.materials[0].collectedOn='2026-02-30';assert.throws(()=>parse(f));assert.throws(()=>parse(JSON.stringify(fixture()).replace('"schemaVersion":1','"__proto__":{},"schemaVersion":1')));});
test('解析不改原始报告和原节点列表',()=>{const f=fixture(),before=JSON.stringify(f),ids=JSON.stringify(nodeIds);parse(f);assert.equal(JSON.stringify(f),before);assert.equal(JSON.stringify(nodeIds),ids);});
