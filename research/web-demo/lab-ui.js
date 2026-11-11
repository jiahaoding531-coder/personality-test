// 实时实验与原报告、采集结果使用独立状态；不自动运行、不自动重试。
const labForm=$('lab-form'),labResult=$('lab-result');
const labLocal=location.protocol==='http:'&&location.hostname==='127.0.0.1'&&location.port==='5186';
let labVersion=0,labBusy=false,labActive=null;
function clearLabResult(){labActive=null;labResult.replaceChildren();labResult.hidden=true;$('lab-query').replaceChildren();$('lab-context').textContent='';$('lab-download').disabled=true;$('lab-error').hidden=true;$('lab-error').textContent='';}
function renderLabResult(){
 const view=getView(labActive,'ALL',$('lab-query').value);
 $('lab-query').replaceChildren(...view.queries.map((q,i)=>new Option(`第 ${i+1} 轮 · ${q.id}`,q.id)));$('lab-query').value=view.query.id;
 const options=labActive.options;
 $('lab-context').textContent=`本次统一条件：${options.maxDistanceKm} 公里 · ${options.remainingMinutes} 分钟 · λ ${options.fairnessWeight} · 种子 ${options.seed}；已实际运行18条合成查询。当前卡片对应 ${view.query.id}，指标统计本次全部18轮。`;
 const cards=view.strategies.map(s=>{
  const m=s.summary.metrics;
  const places=s.selected.length?s.selected.map((n,i)=>`<div class="place"><span class="rank">${i+1}</span><div><h4>${shortName(n)}</h4><small>合成样例 · 非真实景点</small><div class="meta"><span>直线 ${numeric(n.distanceKm,2)} km</span><span>原分 ${numeric(n.baseScore)}</span></div><details><summary>原评分依据</summary><p>兴趣 ${numeric(n.interest)} × 距离 ${numeric(n.distanceFactor)} × 质量 ${numeric(n.qualityFactor)} × 状态 ${numeric(n.stateFactor)} × 天气 ${numeric(n.weatherFactor)}。数值来自Java实际输出，不代表实时现场事实。</p></details></div></div>`).join(''):'<p class="lab-empty">当前轮次没有满足硬约束的候选，返回0张卡片，不扩大距离或放宽时间。</p>';
  return `<article class="strategy" style="--series:${strategies[s.strategy].color}"><div class="strategy-head"><h3>${strategies[s.strategy].title}</h3><p>本次独立运行；模拟计数从零开始。</p></div><div class="places">${places}</div><div class="metrics">${metric('18轮入选分布 Gini',numeric(m.gini))}${metric('原分保留率',pct(s.summary.relevanceRetention))}${metric('预指定测试组份额',pct(m.longTailShare))}${metric('空结果轮次',s.summary.emptyQueries+' / 18')}</div></article>`;
 }).join('');
 labResult.innerHTML=`<div class="strategy-grid">${cards}</div><details class="details"><summary>查看本次运行追溯</summary><p>运行编号 ${esc(labActive.trace.runId)}</p><p class="fieldwork-hash">输入 SHA-256 ${esc(labActive.trace.inputSha256)}</p><p class="fieldwork-hash">原始报告 SHA-256 ${esc(labActive.trace.reportSha256)}</p><p>下载包是输入、报告和追溯信息的JSON副本。校验和对应本机运行目录中的原始文件，副本排版不与原文件逐字节一致。</p></details>`;
 labResult.hidden=false;$('lab-download').disabled=false;
}
function validateLabReply(reply,options){
 const expected=JSON.parse(JSON.stringify(data.input));Object.assign(expected,{seed:options.seed,fairnessWeight:options.fairnessWeight});expected.queries=expected.queries.map(q=>({...q,maxDistanceKm:options.maxDistanceKm,remainingMinutes:options.remainingMinutes}));
 if(JSON.stringify(reply?.input)!==JSON.stringify(expected)||reply?.report?.seed!==options.seed||reply.report.fairnessWeight!==options.fairnessWeight||reply.report.datasetKind!=='SYNTHETIC_TEST_ONLY'||reply.report.measurement!=='SIMULATED_SELECTIONS_EQUAL_POSITION_WEIGHT'||reply.report.variants?.length!==3||['inputSha256','reportSha256'].some(k=>!/^[a-f0-9]{64}$/.test(reply?.trace?.[k]??'')))throw Error('实验结果与当前配置不一致。');
 if(new Set(reply.report.variants.map(v=>v.strategy)).size!==3||reply.report.variants.some(v=>!Object.hasOwn(strategies,v.strategy)))throw Error('实验策略不完整。');
 return {...reply,options};
}
labForm.addEventListener('input',()=>{++labVersion;clearLabResult();$('lab-context').textContent='';$('lab-status').textContent='条件已变化，请点击重新运行。旧响应不会作为新条件的结果。';});
labForm.addEventListener('submit',async event=>{
 event.preventDefault();if(!labLocal||labBusy||!labForm.checkValidity())return;
 const version=++labVersion,options={maxDistanceKm:$('lab-distance').valueAsNumber,remainingMinutes:$('lab-minutes').valueAsNumber,fairnessWeight:$('lab-weight').valueAsNumber,seed:$('lab-seed').valueAsNumber};
 labBusy=true;clearLabResult();$('lab-context').textContent='';$('lab-run').disabled=true;$('lab-status').textContent='正在使用原Java引擎运行18条合成查询…';
 const controller=new AbortController();let timedOut=false,deadline;
 try{
  // 截止时间覆盖连接与JSON读取。race也能隔离不响应abort的晚到结果。
  const timeout=new Promise((_,reject)=>{deadline=setTimeout(()=>{timedOut=true;reject(Error('本机请求超时'));controller.abort();},15000);});
  const work=(async()=>{const response=await fetch('/api/lab/run',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(options),signal:controller.signal});if(!response.ok)throw Error('本机服务未完成实验。');return response.json();})();
  const reply=await Promise.race([work,timeout]);if(version!==labVersion)return;
  labActive=validateLabReply(reply,options);renderLabResult();$('lab-status').textContent='本次Java运行完成。结果只属于这次合成实验，不代表真实用户效果。';
 }catch{
  if(version!==labVersion)return;clearLabResult();$('lab-status').textContent='本次没有可用结果。';$('lab-error').textContent=timedOut?'本机实验请求超时，等待已结束。请检查本机服务后手动重试；不会自动重试或采用晚到结果。':'实验未完成或返回内容与配置不一致。请确认本机新版服务与独立Java构建可用，再手动重试；系统不会自动重试。';$('lab-error').hidden=false;
 }finally{clearTimeout(deadline);labBusy=false;$('lab-run').disabled=!labLocal;}
});
$('lab-query').addEventListener('change',()=>{if(labActive)renderLabResult();});
$('lab-download').addEventListener('click',()=>{
 if(!labActive)return;const {input,report,trace}=labActive,blob=new Blob([JSON.stringify({input,report,trace},null,2)],{type:'application/json'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='TravelMind-本次合成实验复现包.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
});
if(!labLocal){$('lab-run').disabled=true;$('lab-status').textContent='实时重算需要本机服务 http://127.0.0.1:5186/ 。单文件方式仍可使用原报告回放和采集结果查看。';}
