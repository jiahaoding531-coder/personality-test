// 只核对已保存报告，不调用引擎、不创建新推荐，也不登记真实曝光。
export class RankingEvidenceError extends Error {
 constructor(){super('当前报告的排序依据缺失或不一致，无法生成入选解释。');this.name='RankingEvidenceError';}
}
const RE_KEYS=['DISTANCE','RELEVANCE','FAIR_EXPOSURE'];
const RE_FACTORS=['interest','distanceFactor','qualityFactor','stateFactor','weatherFactor'];
const reNeed=ok=>{if(!ok)throw new RankingEvidenceError();};
const reNumber=n=>typeof n==='number'&&Number.isFinite(n);
const reClose=(a,b)=>reNumber(a)&&reNumber(b)&&Math.abs(a-b)<=1e-10*Math.max(1,Math.abs(a),Math.abs(b));

export function createRankingEvidence(value,queryId){
 const {input:c,report:r}=value??{};
 reNeed(c&&r&&r.version==='offline-three-baselines-v1'&&r.measurement==='SIMULATED_SELECTIONS_EQUAL_POSITION_WEIGHT');
 reNeed(r.datasetId===c.datasetId&&r.datasetKind===c.datasetKind&&r.fairnessWeight===c.fairnessWeight&&reNumber(c.fairnessWeight)&&c.fairnessWeight>=0&&c.fairnessWeight<=1);
 reNeed(Array.isArray(c.nodes)&&c.nodes.length<=1000&&Array.isArray(c.queries)&&c.queries.length<=1000&&c.nodes.length*c.queries.length<=50000);
 const nodes=new Map(c.nodes.map(n=>[n.id,n])),queries=new Map(c.queries.map(q=>[q.id,q]));
 reNeed(nodes.size===c.nodes.length&&queries.size===c.queries.length&&queries.has(queryId));
 reNeed(Array.isArray(r.queryOrder)&&r.queryOrder.length===queries.size&&new Set(r.queryOrder).size===queries.size&&r.queryOrder.every(id=>queries.has(id)));
 reNeed(Array.isArray(r.variants)&&r.variants.length===3&&new Set(r.variants.map(v=>v.strategy)).size===3&&r.variants.every(v=>RE_KEYS.includes(v.strategy)));
 const currentIndex=r.queryOrder.indexOf(queryId),query=queries.get(queryId),strategies=[];let common;
 for(const key of RE_KEYS){
  const variant=r.variants.find(v=>v.strategy===key);
  reNeed(Array.isArray(variant.decisions)&&variant.decisions.length===queries.size);
  // Map按场景隔离。不能把最终累计数、其他策略或未来轮次的选择当作此前计数。
  const history=new Map();let current;
  for(let i=0;i<=currentIndex;i++){
   const d=variant.decisions[i],q=queries.get(r.queryOrder[i]);
   reNeed(d&&d.queryId===q.id&&d.stratum===q.stratum&&Array.isArray(d.eligible)&&d.eligible.length<=nodes.size);
   const counts=history.get(q.stratum)??new Map();history.set(q.stratum,counts);
   const seen=new Set(),rows=d.eligible.map(p=>{
    const n=nodes.get(p.id);reNeed(n&&Number.isSafeInteger(p.id)&&p.id>0&&!seen.has(p.id));seen.add(p.id);
    const factors=RE_FACTORS.map(k=>p[k]);
    reNeed(factors.every(f=>reNumber(f)&&f>=0&&f<=1)&&reNumber(p.baseScore)&&p.baseScore>=0&&p.baseScore<=1&&reClose(p.baseScore,factors.reduce((a,b)=>a*b,1)));
    reNeed(reNumber(p.distanceKm)&&p.distanceKm>=0&&p.distanceKm<=q.maxDistanceKm+1e-9&&n.suggestedMinutes<=q.remainingMinutes&&(q.maxTicketPrice===null||n.ticketPrice<=q.maxTicketPrice));
    const priorSelections=counts.get(p.id)??0,baseContribution=(1-c.fairnessWeight)*p.baseScore,fairnessContribution=c.fairnessWeight/(1+priorSelections);
    const expected=key==='DISTANCE'?-p.distanceKm:key==='RELEVANCE'?p.baseScore:baseContribution+fairnessContribution;
    reNeed(reClose(p.priority,expected));
    return {id:p.id,baseScore:p.baseScore,distanceKm:p.distanceKm,factors,priority:p.priority,priorSelections,baseContribution:key==='FAIR_EXPOSURE'?baseContribution:null,fairnessContribution:key==='FAIR_EXPOSURE'?fairnessContribution:null,suggestedMinutes:n.suggestedMinutes,ticketPrice:n.ticketPrice};
   });
   // 这只用于核对v1报告排序，没有替换Java输出，也不修改原分或原selected。
   // 用精确推导的v1排序值核对顺序；容差只用于数值序列化，不能改变同分规则。
   rows.sort((a,b)=>key==='DISTANCE'?a.distanceKm-b.distanceKm||a.id-b.id:key==='RELEVANCE'?b.baseScore-a.baseScore||a.id-b.id:(b.baseContribution+b.fairnessContribution)-(a.baseContribution+a.fairnessContribution)||b.baseScore-a.baseScore||a.id-b.id);
   const expected=rows.slice(0,3).map(p=>p.id);
   reNeed(Array.isArray(d.selected)&&d.selected.length===expected.length&&d.selected.every((id,j)=>id===expected[j]));
   if(i===currentIndex)current={strategy:key,selected:[...d.selected],rows:rows.map((p,j)=>({...p,rank:j+1,selected:d.selected.includes(p.id)}))};
   for(const id of d.selected)counts.set(id,(counts.get(id)??0)+1);
  }
  const comparable=current.rows.map(({id,baseScore,distanceKm,factors})=>({id,baseScore,distanceKm,factors})).sort((a,b)=>a.id-b.id);
  if(common)reNeed(JSON.stringify(common)===JSON.stringify(comparable));else common=comparable;
  strategies.push(current);
 }
 const rel=strategies[1],fair=strategies[2],selected=s=>s.rows.filter(p=>p.selected),sum=(s,k)=>selected(s).reduce((a,p)=>a+p[k],0),mean=s=>s.selected.length?sum(s,'distanceKm')/s.selected.length:null;
 return {query:{...query,states:[...query.states]},datasetKind:c.datasetKind,fairnessWeight:c.fairnessWeight,strategies,comparison:{eligibleCount:rel.rows.length,addedIds:fair.selected.filter(id=>!rel.selected.includes(id)),removedIds:rel.selected.filter(id=>!fair.selected.includes(id)),sharedIds:fair.selected.filter(id=>rel.selected.includes(id)),baseScoreDelta:sum(fair,'baseScore')-sum(rel,'baseScore'),meanDistanceKmDelta:mean(fair)===null||mean(rel)===null?null:mean(fair)-mean(rel)}};
}
