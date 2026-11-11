// 仅选择已保存的实验结果，不在浏览器重新评分，也不记录曝光。
export function getView(data, scope='ALL', queryId) {
  const {report,input}=data;
  if(scope!=='ALL' && !Object.hasOwn(report.variants[0].strata,scope)) throw new Error('场景不存在');
  const queries=report.queryOrder.map(id=>input.queries.find(q=>q.id===id))
    .filter(q=>scope==='ALL'||q.stratum===scope);
  const query=queries.find(q=>q.id===queryId)||queries[0];
  const strategies=report.variants.map(v=>{
    const summary=scope==='ALL'?v.aggregate:v.strata[scope];
    const decision=v.decisions.find(d=>d.queryId===query.id);
    const selected=decision.selected.map(id=>({
      ...input.nodes.find(n=>n.id===id),...decision.eligible.find(n=>n.id===id)
    }));
    const distribution=Object.entries(summary.opportunities).filter(([,n])=>n>0)
      .map(([id,opportunities])=>({id:Number(id),opportunities,selections:summary.selections[id]??0}));
    return {strategy:v.strategy,summary,decision,selected,distribution};
  });
  return {scope,queries,query,strategies};
}
export function displayNumber(value,digits=3) {
  return value===null||value===undefined||!Number.isFinite(value)?'未知':value.toFixed(digits);
}

// 只索引已完成的实验组合。这里没有重新评分、累加曝光或网络请求。
export function getRobustnessView(data,{scope='ALL',weight=.35,seed,queryId}={}) {
  const report=data.robustness;
  if(report?.datasetKind!=='SYNTHETIC_TEST_ONLY'||report.measurement!=='SIMULATED_SELECTIONS_EQUAL_POSITION_WEIGHT')
    throw new Error('只允许明确标记的合成入选实验');
  const {seeds,fairnessWeights:weights}=report.plan;
  if(!weights.includes(weight)) throw new Error('权重不在已运行计划中');
  seed=seed??seeds[0];
  if(!seeds.includes(seed)) throw new Error('种子不在已运行计划中');
  const keys=new Set(report.runs.map(r=>`${r.seed}:${r.fairnessWeight}`));
  if(report.runs.length!==seeds.length*weights.length||keys.size!==report.runs.length||
     seeds.some(s=>weights.some(w=>!keys.has(`${s}:${w}`)))) throw new Error('运行组合缺少或重复');
  const run=report.runs.find(r=>r.seed===seed&&r.fairnessWeight===weight);
  if(run.report.seed!==seed||run.report.fairnessWeight!==weight||run.report.datasetId!==data.input.datasetId)
    throw new Error('运行组合与数据来源不一致');
  const scopes=Object.keys(run.report.variants[0].strata);
  if(scope!=='ALL'&&!scopes.includes(scope)) throw new Error('场景不存在');
  const matches=(row,w)=>row.fairnessWeight===w&&
    (scope==='ALL'?row.scope==='AGGREGATE'&&row.stratum===null:row.scope==='STRATUM'&&row.stratum===scope);
  const summary=(w,strategy)=>{
    const found=report.summaries.filter(s=>matches(s,w)&&s.strategy===strategy);
    if(found.length!==1) throw new Error('策略汇总缺少或重复');
    return found[0];
  };
  const paired=report.pairedFairMinusRelevance.filter(s=>matches(s,weight));
  if(paired.length!==1) throw new Error('配对汇总缺少或重复');
  return {
    scope,weight,seed,scopes,seeds,weights,runCount:report.runs.length,
    strategies:['DISTANCE','RELEVANCE','FAIR_EXPOSURE'].map(s=>summary(weight,s)),
    paired:paired[0],trend:weights.map(w=>({weight:w,summary:summary(w,'FAIR_EXPOSURE')})),
    replay:getView({input:data.input,report:run.report},scope,queryId)
  };
}

// 单位集中处理：份额本身用百分比，份额差值用百分点；缺失不转成0。
export function formatMetricRange(summary,{percent=false,signed=false,digits=percent?2:4}={}) {
  const fmt=value=>{
    if(value===null||value===undefined||!Number.isFinite(value)) return '未定义';
    const scaled=percent?value*100:value;
    const text=(signed&&scaled>0?'+':'')+scaled.toFixed(digits);
    return text+(percent?(signed?' 个百分点':'%'):'');
  };
  return {
    meanText:fmt(summary?.mean),
    rangeText:Number.isFinite(summary?.min)&&Number.isFinite(summary?.max)?`${fmt(summary.min)} — ${fmt(summary.max)}`:'未定义',
    countsText:`有效 ${summary?.validCount??'未知'} / 缺失 ${summary?.missingCount??'未知'}`
  };
}
