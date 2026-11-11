// 此文件由构建脚本内嵌。所有交互仅选择报告，不发出请求、不更新实验状态。
const robMetrics=[['gini','入选分布 Gini',false],['relevanceRetention','原分保留率',true],['longTailShare','预指定测试组份额',true],['coverage','可行节点覆盖率',true]];
let robustView;
const metricRangeHtml=(summary,percent=false,signed=false)=>{
  const f=formatMetricRange(summary,{percent,signed});
  return `<strong>${esc(f.meanText)}</strong><small>最小—最大：${esc(f.rangeText)}</small><small>${esc(f.countsText)}</small>`;
};
function renderRobustness(){
  try{
    robustView=getRobustnessView(data,{scope:$('rob-scope').value,weight:Number($('rob-weight').value),seed:Number($('rob-seed').value),queryId:$('rob-query').value});
    $('rob-error').hidden=true;$('rob-content').hidden=false;
    const v=robustView, label=v.scope==='ALL'?'全部场景':v.scope;
    $('rob-summary-caption').textContent=`${label} · λ = ${v.weight} · ${v.seeds.length} 次运行的汇总 · 每次 ${v.replay.queries.length} 次查询`;
    $('rob-summaries').innerHTML=v.strategies.map(s=>`<article class="rob-card" style="--series:${strategies[s.strategy].color}"><h3>${strategies[s.strategy].title}</h3>${robMetrics.map(([key,title,percent])=>`<div class="rob-stat"><span class="metric-label">${title} · 均值</span>${metricRangeHtml(s.metrics[key],percent)}</div>`).join('')}</article>`).join('');
    $('rob-paired').innerHTML=robMetrics.slice(0,3).map(([key,title,percent])=>`<div><b>${title}</b>${metricRangeHtml(v.paired.metrics[key],percent,true)}</div>`).join('');
    $('rob-trend').innerHTML=v.trend.map(row=>`<tr data-selected="${row.weight===v.weight}"><th scope="row">${row.weight}${row.weight===v.weight?' · 当前':''}</th>${robMetrics.slice(0,3).map(([key,,percent])=>`<td>${metricRangeHtml(row.summary.metrics[key],percent)}</td>`).join('')}</tr>`).join('');
    const eligible=v.replay.strategies[0].summary.metrics.eligibleNodes;
    $('rob-interpretation').textContent=eligible<=3?'当前场景只有三个可行节点，每轮全部入选，三种策略 Gini 都为零；这不是公平重排带来的额外改善。':v.weight===0?'权重为0时，公平组退化为相关性排序，配对差值为0。这是边界校验，不是新的公平收益。':'不要只挑有利权重或种子。分布均衡与预指定组份额不是同一目标，应同时报告原相关性损失和各场景差异。';
    $('rob-query').replaceChildren(...v.replay.queries.map((q,i)=>new Option(`第 ${i+1} 轮 · ${q.id.replace('synthetic-','')}`,q.id)));
    $('rob-query').value=v.replay.query.id;
    const ix=v.replay.queries.indexOf(v.replay.query),q=v.replay.query;
    $('rob-previous').disabled=ix===0;$('rob-next').disabled=ix===v.replay.queries.length-1;
    $('rob-context').textContent=`单次回放 · 种子 ${v.seed} · λ ${v.weight} · ${q.stratum} · 最远 ${q.maxDistanceKm} 公里 · 可用 ${q.remainingMinutes} 分钟 · 门票上限 ${q.maxTicketPrice} 元 · ${q.states.includes('TIRED')?'疲惫':'无额外状态'}`;
    $('rob-replay').innerHTML=v.replay.strategies.map(s=>`<article class="rob-card" style="--series:${strategies[s.strategy].color}"><h3>${strategies[s.strategy].title}</h3><div class="places">${s.selected.map((n,i)=>`<div class="place"><span class="rank">0${i+1}</span><div><h4>${shortName(n)}</h4><small>合成节点 · 非真实景点</small><div class="meta"><span>直线 ${numeric(n.distanceKm,2)} km</span><span>原评分 ${numeric(n.baseScore)}</span></div></div></div>`).join('')||'<p>本轮无可行候选</p>'}</div><p class="rob-note">本次运行整个${esc(label)}的 Gini：${numeric(s.summary.metrics.gini,4)}；此值不是十次运行均值。</p></article>`).join('');
    $('rob-live').textContent=`已回放${label}，权重${v.weight}，种子${v.seed}，第${ix+1}轮。`;
  }catch(error){
    $('rob-content').hidden=true;$('rob-error').hidden=false;
    $('rob-error').textContent=`报告暂不能展示：${error.message}。没有使用其他场景或旧结果替代。`;
  }
}
Object.keys(data.report.variants[0].strata).forEach(s=>$('rob-scope').add(new Option(s,s)));
data.robustness.plan.fairnessWeights.forEach(w=>$('rob-weight').add(new Option(String(w),String(w))));
data.robustness.plan.seeds.forEach(s=>$('rob-seed').add(new Option(String(s),String(s))));
$('rob-weight').value='0.35';
for(const id of ['rob-scope','rob-weight','rob-seed'])$(id).addEventListener('change',()=>{$('rob-query').value='';renderRobustness();});
$('rob-query').addEventListener('change',renderRobustness);
for(const [id,step] of [['rob-previous',-1],['rob-next',1]])$(id).addEventListener('click',()=>{
  const r=robustView.replay,q=r.queries[r.queries.indexOf(r.query)+step];
  if(q){$('rob-query').value=q.id;renderRobustness();}
});
$('rob-provenance').innerHTML=`报告版本：<code>${esc(data.robustness.version)}</code><br>报告 SHA-256：<code>${data.provenance.robustnessReportSha256}</code><br>实验计划 SHA-256：<code>${data.provenance.robustnessPlanSha256}</code><br>原文件：offline-robustness/final-20261028-a/report.json；构建时核验原始字节。`;
$('rob-download').addEventListener('click',()=>{
  const blob=new Blob([JSON.stringify(data.robustness,null,2)],{type:'application/json'});
  const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='TravelMind-多种子合成实验数据副本.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
});
renderRobustness();
