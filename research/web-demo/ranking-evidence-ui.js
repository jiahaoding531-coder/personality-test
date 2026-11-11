// 确定性解释仅绑定当前导入报告与当前轮次，不请求LLM，不存浏览器数据。
const rankingPanel=(()=>{
 let active=null,opener=null;
 const panel=$('ranking-evidence'),body=$('ranking-evidence-body'),status=$('ranking-evidence-status');
 const text=(tag,content,className)=>{const n=document.createElement(tag);n.textContent=content;if(className)n.className=className;return n;};
 const fmt=n=>numeric(n,4),delta=(n,unit='')=>n===null?'未定义':(n>0?'+':'')+fmt(n)+unit;
 function close(){panel.hidden=true;body.replaceChildren();$('ranking-evidence-title').textContent='';delete panel.dataset.nodeId;delete panel.dataset.strategy;for(const b of $('result-cards').querySelectorAll('[data-result-explain]'))b.setAttribute('aria-expanded','false');opener=null;}
 function reset(){active=null;close();status.textContent='';status.hidden=true;status.setAttribute('role','status');}
 function prepare(value,queryId){
  reset();
  try{active={value,evidence:createRankingEvidence(value,queryId)};status.textContent='本轮入选依据已与v1报告数值核对。点击卡片“为什么入选”查看；这不是原始材料真实性认证。';}
  catch{status.textContent='当前报告的排序依据缺失或不一致，仅保留报告回放，不生成入选解释。';status.setAttribute('role','alert');}
  status.hidden=false;for(const b of $('result-cards').querySelectorAll('[data-result-explain]'))b.disabled=!active;
 }
 function open(strategy,id,button){
  close();if(!active)return;
  const {evidence:e,value}=active,s=e.strategies.find(s=>s.strategy===strategy),p=s?.rows.find(p=>p.id===id&&p.selected);
  if(!p)return;
  const name=id=>resultNodeName(value,value.input.nodes.find(n=>n.id===id));
  const q=e.query,rel=e.strategies.find(s=>s.strategy==='RELEVANCE').rows.find(n=>n.id===id);
  $('ranking-evidence-title').textContent=`${name(id)} · 为什么本轮入选`;
  panel.dataset.nodeId=String(id);panel.dataset.strategy=strategy;
  body.append(text('p',`${strategies[strategy].title} · 查询 ${q.id} · 场景 ${q.stratum} · ${value.kindLabel}`,'notice'));
  body.append(text('p',`这是单节点候选：直线 ${fmt(p.distanceKm)} ≤ ${q.maxDistanceKm} 公里；建议游览 ${p.suggestedMinutes} ≤ ${q.remainingMinutes} 分钟；门票记录 ${p.ticketPrice} 元${q.maxTicketPrice===null?'，本轮未设门票上限':` ≤ ${q.maxTicketPrice} 元上限`}。三张Top3是备选，不是能够在剩余时间内依次游完的路线。`));
  const stateLabels={TIRED:'疲惫',HUNGRY:'想吃饭',WANT_WALK:'想走走',QUIET:'偏好安静',PHOTO:'想拍照',CULTURE:'想看文化',NATURE:'想看自然'};
  body.append(text('p',`本次保存的状态：${q.states.length?q.states.map(s=>stateLabels[s]??s).join('、'):'无额外状态'}。这些是本轮条件，不覆盖长期画像，也不表示现场的实时状况。`,'notice'));
  body.append(text('h4','① 原推荐引擎：保存的五因子'));
  const factors=document.createElement('dl');factors.className='ranking-factors';
  ['兴趣匹配','距离因子','质量因子','状态因子','天气因子'].forEach((label,i)=>{const item=document.createElement('div');item.append(text('dt',label),text('dd',fmt(p.factors[i])));factors.append(item);});body.append(factors);
  body.append(text('p',`${p.factors.map(fmt).join(' × ')} = 原分 ${fmt(p.baseScore)}。已用未舍入值核对乘积，显示值经四位小数格式化。`));
  body.append(text('p','因子来自该次保存的评分记录，不是实时客流、排队、营业或天气事实。查看文件没有完整画像和地点属性快照，不能进一步声称某项个人偏好或静态标签已获独立证实；原分也不是用户满意度。','notice'));
  body.append(text('h4','② 当前策略：先过硬条件，再排序'));
  if(strategy==='FAIR_EXPOSURE'){
   body.append(text('p',`此前同场景模拟入选 ${p.priorSelections} 次，只统计公平策略在 ${q.id} 之前的记录，排除其他场景和未来轮次。`));
   body.append(text('p',`(1 − λ) × 原分 + λ ÷ (1 + 此前次数)；λ=${e.fairnessWeight}。本节点为 ${fmt(p.baseContribution)} + ${fmt(p.fairnessContribution)} = 重排分 ${fmt(p.priority)}。奖励不改写原分，也不会让超出硬约束的节点重新入选。`));
  }else body.append(text('p',strategy==='DISTANCE'?'距离策略按保存的直线距离升序。同距离按节点ID升序；保存排序值为负距离，不是路线里程。':'相关性策略按保存的原分降序，同分按节点ID升序；不会增加公平奖励。'));
  body.append(text('p',`本策略候选排名第 ${p.rank}，相关性候选排名第 ${rel.rank}。取前3名，少于3个就只取实际数量；公平同分时先比原分，再按节点ID。`));
  const candidates=document.createElement('div');candidates.className='ranking-candidates';candidates.setAttribute('aria-label','本轮全部可行候选排序核对');
  for(const row of s.rows){const item=document.createElement('article');item.className='ranking-row';item.dataset.selected=String(row.selected);item.append(text('strong',`第${row.rank} · ${name(row.id)}${row.selected?' · 入选':' · 未入选'}`));for(const [label,v] of [['原分',fmt(row.baseScore)],['直线距离',fmt(row.distanceKm)+' km'],['此前同场景模拟入选',row.priorSelections+' 次'],['保存排序值',fmt(row.priority)]])item.append(text('span',`${label} ${v}`));candidates.append(item);}body.append(candidates);
  body.append(text('h4','③ 公平与相关性：这轮改变了什么'));
  const a=e.comparison,join=ids=>ids.length?ids.map(name).join('、'):'无';
  body.append(text('p',`公平相对相关性：新增 ${join(a.addedIds)}；让出 ${join(a.removedIds)}。入选原分总和差 ${delta(a.baseScoreDelta)}；平均直线距离差 ${delta(a.meanDistanceKmDelta,' 公里')}。差值均为公平减相关性，不是百分比提升、路线距离或满意度变化。`));
  body.append(text('p',a.eligibleCount<=3?'本轮可行候选不超过3个，全部入选不能证明公平重排有额外收益。':'本轮只反映一组候选的选择和数值取舍；公平收益需要结合整个场景的机会分布和多轮统计，不由一次换入节点证明。','notice'));
  body.append(text('p','这不是实际曝光、导航或到访记录；不会推断“人少”“不用排队”“正在营业”或交通时间。合成演示不是真实研究结论。','notice'));
  panel.hidden=false;opener=button;button.setAttribute('aria-expanded','true');$('ranking-evidence-title').focus();
 }
 $('ranking-evidence-close').addEventListener('click',()=>{const previous=opener;close();if(previous?.isConnected)previous.focus();});
 $('result-cards').addEventListener('click',event=>{const b=event.target.closest?.('button[data-result-explain]');if(b&&!b.disabled)open(b.dataset.strategy,Number(b.dataset.nodeId),b);});
 return {reset,prepare};
})();
