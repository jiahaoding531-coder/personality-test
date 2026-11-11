// 文件只保留在页面内存，独立于内置报告、采集草稿和实时实验状态。
(()=>{
 let generation=0,value=null;
 function reset(){generation++;value=null;rankingPanel.reset();$('result-output').hidden=true;for(const id of ['result-kind','result-context','result-note','result-provenance','result-cards','result-distribution','result-query','result-scope'])$(id).replaceChildren();$('result-download').disabled=true;$('result-error').hidden=true;$('result-status').dataset.loading='false';}
 function renderResult(){
  if(!value)return;
  rankingPanel.reset();
  const scope=$('result-scope').value||'ALL',selected=$('result-query').value||undefined,view=getResultView(value,scope,selected);
  $('result-query').replaceChildren(...view.queries.map((q,i)=>new Option(`第${i+1}轮 · ${q.id}`,q.id)));$('result-query').value=view.query.id;
  const ix=view.queries.findIndex(q=>q.id===view.query.id);$('result-previous').disabled=ix===0;$('result-next').disabled=ix===view.queries.length-1;
  $('result-kind').textContent=value.kindLabel;
  const q=view.query;$('result-context').textContent=`本轮直线距离上限 ${q.maxDistanceKm} 公里 · 可用 ${q.remainingMinutes} 分钟 · 门票上限 ${q.maxTicketPrice===null?'未设置':q.maxTicketPrice+' 元'} · 时刻 ${q.localTime} · 可行候选 ${view.strategies[0].decision.eligible.length} 个`;
  $('result-cards').innerHTML=view.strategies.map(s=>{const meta=strategies[s.strategy],a=s.summary,m=a.metrics;return `<article class="rob-card" style="--series:${meta.color}"><h3>${meta.title}</h3><p class="notice">当前范围 ${a.queries} 次查询 · ${a.emptyQueries} 次无结果</p><div class="metrics">${metric('模拟入选 Gini',numeric(m.gini))}${metric('长尾组份额',pct(m.longTailShare))}${metric('引擎分保留比',pct(a.relevanceRetention))}${metric('可行节点覆盖率',pct(m.coverage))}</div><div class="places">${s.selected.length?s.selected.map((n,i)=>`<div class="place"><span class="rank">${i+1}</span><div><h4>${esc(resultNodeName(value,n))}</h4><small>${value.input.datasetKind==='SYNTHETIC_TEST_ONLY'?'合成节点 · 非真实地点':esc(n.category)+' · 提交记录未认证'}</small><div class="meta"><span>直线 ${numeric(n.distanceKm,2)} km</span><span>原分 ${numeric(n.baseScore)}</span></div><button type="button" data-result-explain aria-expanded="false" data-strategy="${s.strategy}" data-node-id="${n.id}" aria-controls="ranking-evidence">为什么入选</button></div></div>`).join(''):'<p class="notice">本轮无可行入选，不补造地点。</p>'}</div></article>`;}).join('');
  rankingPanel.prepare(value,view.query.id);
  const ids=[...new Set(view.strategies.flatMap(s=>s.distribution.map(n=>n.id)))].sort((a,b)=>a-b),max=Math.max(1,...view.strategies.flatMap(s=>s.distribution.map(n=>n.selections)));
  $('result-distribution').innerHTML=ids.length?ids.map(id=>`<div class="bar-row"><div class="node-label">${esc(resultNodeName(value,value.input.nodes.find(n=>n.id===id)))}<small>${value.input.longTailPlaceIds.includes(id)?'声明的长尾组':'其他节点'}</small></div><div class="bar-series">${view.strategies.map(s=>{const n=s.distribution.find(n=>n.id===id),meta=strategies[s.strategy];return `<div class="bar-track"><span class="result-series">${meta.title}</span><div class="track"><div class="fill" style="--series:${meta.color};width:${n.selections/max*100}%"></div></div><span class="bar-count">${n.selections} 次</span></div>`;}).join('')}</div></div>`).join(''):'<p>当前范围没有可行候选，指标保持未定义。</p>';
  $('result-note').textContent=`统计范围：${scope==='ALL'?'全部场景':scope}。累计模拟入选与上方单轮Top3分开。${view.strategies[0].decision.eligible.length<=3?'本轮可行候选不超过3个，不把全部入选解释为公平重排的额外收益。':''}`;
  $('result-provenance').textContent=`数据集 ${value.input.datasetId}；算法 ${value.report.version}；种子 ${value.input.seed}；λ ${value.input.fairnessWeight}。原报告 SHA-256：${value.hashes.reportSha256}。原输入 SHA-256（来源声明，本页未读完整输入）：${value.hashes.inputSha256}。`;
 }
 async function loadResult(read,source){
  reset();const mine=generation;let timer;$('result-clear').disabled=false;$('result-status').textContent='正在读取并核对结果…';$('result-status').dataset.loading='true';
  try{
   const deadline=new Promise((_,reject)=>{timer=setTimeout(()=>reject(new ResultViewError('读取或校验超时，请重新选择文件。')),10000);});
   const parsed=await Promise.race([Promise.resolve().then(read).then(raw=>validateResultView(raw)),deadline]);
   if(mine!==generation)return;value=parsed;$('result-scope').replaceChildren(new Option('全部场景','ALL'),...[...new Set(value.input.queries.map(q=>q.stratum))].map(s=>new Option(s,s)));
   $('result-output').hidden=false;$('result-download').disabled=false;renderResult();$('result-status').textContent=`${source} · 已核对报告与上下文摘要；原始材料未在本页重验。`;
  }catch(error){if(mine!==generation)return;value=null;$('result-error').textContent=error instanceof ResultViewError?error.message:'未能读取结果文件，请重新选择。本页不显示底层文件内容或错误详情。';$('result-error').hidden=false;$('result-status').textContent='未显示结果，没有复用旧卡片或旧指标。';}
  finally{clearTimeout(timer);if(mine===generation){$('result-status').dataset.loading='false';$('result-file').value='';}}
 }
 $('result-file').addEventListener('change',()=>{const file=$('result-file').files?.[0];if(!file)return;if(!Number.isFinite(file.size)||file.size<0||file.size>8_000_000){reset();$('result-error').textContent='结果文件过大或大小无效，最大8MB。';$('result-error').hidden=false;$('result-file').value='';$('result-status').textContent='未读取文件。';return;}loadResult(()=>file.text(),'本地文件');});
 $('result-sample').addEventListener('click',()=>loadResult(()=>JSON.stringify(data.resultExample),'内置原Java合成报告，不是真实验证'));
 $('result-clear').addEventListener('click',()=>{reset();$('result-file').value='';$('result-clear').disabled=true;$('result-status').textContent='本页结果已清空，未保存在浏览器存储。';});
 $('result-scope').addEventListener('change',()=>{$('result-query').value='';renderResult();});$('result-query').addEventListener('change',renderResult);
 for(const [button,step] of [['result-previous',-1],['result-next',1]])$(button).addEventListener('click',()=>{if(!value)return;const v=getResultView(value,$('result-scope').value,$('result-query').value),ix=v.queries.findIndex(q=>q.id===v.query.id),q=v.queries[ix+step];if(q){$('result-query').value=q.id;renderResult();}});
 $('result-download').addEventListener('click',()=>{if(!value)return;const blob=new Blob([resultBrief(value)],{type:'text/markdown;charset=utf-8'}),url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download=`TravelMind-实验答辩摘要-${value.input.datasetKind==='SYNTHETIC_TEST_ONLY'?'合成演示':'提交数据-未认证'}.md`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});
})();
