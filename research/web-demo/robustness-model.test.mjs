import {fileURLToPath} from 'node:url';
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import * as model from './demo-model.mjs';

const base=fileURLToPath(new URL('./fixtures/robustness/',import.meta.url));
const data={input:JSON.parse(fs.readFileSync(base+'input.json')),robustness:JSON.parse(fs.readFileSync(base+'report.json'))};
const view=(options={}, source=data)=>{
  assert.equal(typeof model.getRobustnessView,'function','尚未实现多种子报告选择能力');
  return model.getRobustnessView(source,options);
};
const range=(summary,options={})=>{
  assert.equal(typeof model.formatMetricRange,'function','尚未实现指标范围与缺失值表达');
  return model.formatMetricRange(summary,options);
};

test('计划完整呈现50个已运行组合及10种子、5权重',()=>{
  const v=view();
  assert.equal(v.runCount,50); assert.equal(v.seeds.length,10);
  assert.deepEqual(v.weights,[0,.15,.35,.6,1]); assert.equal(v.weight,.35);
  assert.equal(v.replay.queries.length,18);
});
test('多种子汇总直接采用原记录，不能混入旧的单种子数值',()=>{
  const v=view(),fair=v.strategies.find(x=>x.strategy==='FAIR_EXPOSURE');
  assert.equal(fair.metrics.gini.mean,0.16111111111111107);
  assert.equal(fair.metrics.gini.min,0.12345679012345678);
  assert.equal(fair.metrics.gini.max,0.19135802469135801);
  assert.equal(fair.metrics.gini.validCount,10);
  assert.equal(fair.metrics.relevanceRetention.mean,0.9913681277812657);
});
test('切换种子只改变单次回放，汇总保持相同',()=>{
  const a=view({seed:2026102601}),b=view({seed:2026102610});
  assert.deepEqual(a.strategies,b.strategies);
  assert.notDeepEqual(a.replay.queries.map(q=>q.id),b.replay.queries.map(q=>q.id));
  assert.equal(b.seed,2026102610);
});
test('权重为零时公平组和相关性组一致，配对差值为零',()=>{
  const v=view({weight:0});
  assert.equal(v.paired.metrics.gini.mean,0);
  assert.equal(v.paired.metrics.relevanceRetention.mean,0);
  assert.deepEqual(v.replay.strategies[1].selected.map(x=>x.id),v.replay.strategies[2].selected.map(x=>x.id));
});
test('换场景使不属于新场景的旧查询失效，不混用总体指标',()=>{
  const v=view({scope:'合成近距离场景',queryId:'synthetic-q08'});
  assert.equal(v.replay.queries.length,6); assert.equal(v.replay.query.stratum,'合成近距离场景');
  for(const s of v.strategies) assert.equal(s.metrics.gini.mean,0);
  for(const s of v.replay.strategies) assert.equal(s.summary.metrics.eligibleNodes,3);
});
test('所有单次回放保持报告入选顺序和距离硬约束',()=>{
  const v=view({seed:2026102607,weight:.6,queryId:'synthetic-q08'});
  const report=data.robustness.runs.find(r=>r.seed===v.seed&&r.fairnessWeight===v.weight).report;
  for(const s of v.replay.strategies){
    assert.deepEqual(s.selected.map(n=>n.id),report.variants.find(x=>x.strategy===s.strategy).decisions.find(d=>d.queryId==='synthetic-q08').selected);
    for(const n of s.selected) assert.ok(n.distanceKm<=v.replay.query.maxDistanceKm);
  }
});
test('配对差值直接读取对应记录，保留负向结果',()=>{
  const v=view();
  const p=data.robustness.pairedFairMinusRelevance.find(x=>x.fairnessWeight===.35&&x.scope==='AGGREGATE');
  assert.deepEqual(v.paired,p);
  assert.ok(v.paired.metrics.longTailShare.mean<0);
  assert.ok(v.paired.metrics.relevanceRetention.mean<0);
});
test('权重对照表包含全部权重，场景口径一致',()=>{
  const v=view({scope:'合成宽松场景'});
  assert.equal(v.trend.length,5);
  for(const row of v.trend){
    assert.equal(row.summary.scope,'STRATUM'); assert.equal(row.summary.stratum,'合成宽松场景');
    assert.equal(row.summary.strategy,'FAIR_EXPOSURE');
  }
});
test('拒绝未运行的权重、旧种子和不存在的场景',()=>{
  assert.throws(()=>view({weight:.2}),/权重/);
  assert.throws(()=>view({weight:'0.35'}),/权重/);
  assert.throws(()=>view({seed:20261026}),/种子/);
  assert.throws(()=>view({scope:'其他'}),/场景/);
});
test('缺少或重复运行组合时拒绝展示完整实验',()=>{
  const missing=structuredClone(data); missing.robustness.runs.pop();
  assert.throws(()=>view({},missing),/运行组合/);
  const duplicate=structuredClone(data); duplicate.robustness.runs[0]=duplicate.robustness.runs[1];
  assert.throws(()=>view({},duplicate),/运行组合/);
});
test('缺少策略汇总或配对汇总时明确拒绝',()=>{
  const a=structuredClone(data);
  a.robustness.summaries=a.robustness.summaries.filter(x=>!(x.fairnessWeight===.35&&x.scope==='AGGREGATE'&&x.strategy==='RELEVANCE'));
  assert.throws(()=>view({},a),/策略汇总/);
  const b=structuredClone(data); b.robustness.pairedFairMinusRelevance=[];
  assert.throws(()=>view({},b),/配对汇总/);
});
test('不把真实或来源不同的数据混进合成实验面板',()=>{
  const d=structuredClone(data); d.robustness.datasetKind='USER_SUPPLIED';
  assert.throws(()=>view({},d),/合成/);
});
test('缺失范围保留未定义，零与有效缺失数正确表达',()=>{
  const missing=range({mean:null,min:null,max:null,validCount:0,missingCount:10});
  assert.equal(missing.meanText,'未定义'); assert.equal(missing.rangeText,'未定义');
  assert.equal(missing.countsText,'有效 0 / 缺失 10');
  const zero=range({mean:0,min:0,max:0,validCount:10,missingCount:0});
  assert.equal(zero.meanText,'0.0000'); assert.equal(zero.rangeText,'0.0000 — 0.0000');
});
test('份额差值以带符号百分点呈现，不误称提升率',()=>{
  const r=range({mean:-.012,min:-.02,max:.01,validCount:10,missingCount:0},{percent:true,signed:true});
  assert.equal(r.meanText,'-1.20 个百分点');
  assert.equal(r.rangeText,'-2.00 个百分点 — +1.00 个百分点');
});
test('反复切换不改写结果、不增加曝光或运行次数',()=>{
  const before=JSON.stringify(data);
  view();view({weight:1,seed:2026102610});view({scope:'合成近距离场景'});
  assert.equal(JSON.stringify(data),before);
});
