import {fileURLToPath} from 'node:url';
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { getView, displayNumber } from './demo-model.mjs';

const base = fileURLToPath(new URL('./fixtures/baseline/',import.meta.url));
const reportBytes = fs.readFileSync(base+'report.json');
const report = JSON.parse(reportBytes);
const input = JSON.parse(fs.readFileSync(base+'input.json'));
const manifest = JSON.parse(fs.readFileSync(base+'manifest.json'));
const data = {report,input};

test('源报告与已有校验清单一致',()=>{
  assert.equal(crypto.createHash('sha256').update(reportBytes).digest('hex'),manifest.reportSha256);
});
test('总体视图使用全部查询及报告原始指标',()=>{
  const view=getView(data,'ALL');
  assert.equal(view.queries.length,18);
  assert.equal(view.strategies.length,3);
  assert.equal(view.strategies[0].summary.metrics.gini,0.5);
  assert.equal(view.strategies[0].summary.metrics.selections,54);
});
test('切换场景只读取该场景统计，旧查询失效后选本场景第一轮',()=>{
  const view=getView(data,'合成近距离场景','synthetic-q08');
  assert.equal(view.queries.length,6);
  assert.equal(view.query.stratum,'合成近距离场景');
  for(const s of view.strategies){
    assert.equal(s.summary.queries,6);
    assert.equal(s.summary.metrics.eligibleNodes,3);
    assert.equal(s.selected.length,3);
  }
});
test('逐轮卡片严格采用记录的入选顺序和原始分数',()=>{
  const view=getView(data,'ALL','synthetic-q08');
  assert.deepEqual(view.strategies[0].selected.map(x=>x.id),[1,2,3]);
  assert.deepEqual(view.strategies[1].selected.map(x=>x.id),[4,6,5]);
  for(const s of view.strategies) for(const n of s.selected){
    assert.ok(n.distanceKm<=view.query.maxDistanceKm);
    assert.match(n.name,/合成/);
    assert.equal(n.baseScore,s.decision.eligible.find(c=>c.id===n.id).baseScore);
  }
});
test('从未可行节点不补成零曝光节点',()=>{
  const view=getView(data,'合成近距离场景');
  for(const s of view.strategies) assert.deepEqual(s.distribution.map(x=>x.id),[1,2,3]);
});
test('未知指标保留未知，数值零保留零',()=>{
  assert.equal(displayNumber(null),'未知');
  assert.equal(displayNumber(undefined),'未知');
  assert.equal(displayNumber(0),'0.000');
});
test('非法场景被拒绝，不混入总体数据',()=>assert.throws(()=>getView(data,'不存在的场景'),/场景不存在/));
test('重复回放不改变报告或累计生成虚假曝光',()=>{
  const before=JSON.stringify(data);
  getView(data,'ALL');getView(data,'合成宽松场景','synthetic-q08');getView(data,'ALL');
  assert.equal(JSON.stringify(data),before);
});
