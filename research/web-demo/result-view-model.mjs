import {getView} from './demo-model.mjs';
const RV_STRATEGIES=['DISTANCE','RELEVANCE','FAIR_EXPOSURE'];
export class ResultViewError extends Error {}
function rvNeed(ok,message){if(!ok)throw new ResultViewError(message);}
function rvObject(v,keys){rvNeed(v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).every(k=>keys.includes(k)),'对象类型或字段不符合结果契约');}
function rvText(v,max=250){return typeof v==='string'&&v.trim().length>0&&v.length<=max;}
function rvNum(v,min=0,max=1){return typeof v==='number'&&Number.isFinite(v)&&v>=min&&v<=max;}
function rvInt(v,min=0,max=Number.MAX_SAFE_INTEGER){return Number.isSafeInteger(v)&&v>=min&&v<=max;}
function rvEqual(a,b){return a===null||b===null?a===b:rvNum(a,-1e15,1e15)&&rvNum(b,-1e15,1e15)&&Math.abs(a-b)<=1e-9*Math.max(1,Math.abs(a),Math.abs(b));}
function rvJson(text,limit){
 rvNeed(typeof text==='string'&&new TextEncoder().encode(text).length<=limit,'结果文件过大或文本无效');
 let value;try{value=JSON.parse(text);}catch{throw new ResultViewError('文件不是有效JSON');}
 // JSON.parse不报告重复键；这里扫描已通过语法校验的文本，含Unicode转义的同义键也去重。
 let i=0,nodes=0;const white=()=>{while(/\s/.test(text[i]??'')&&i<text.length)i++;};
 const string=()=>{const start=i++;while(i<text.length){if(text[i]==='\\'){i+=2;continue;}if(text[i++]==='"')break;}return JSON.parse(text.slice(start,i));};
 const visit=depth=>{rvNeed(depth<=32&&++nodes<=200000,'JSON结构过深或记录过多');white();const ch=text[i++];
  if(ch==='{'){const seen=new Set();white();if(text[i]==='}'){i++;return;}while(i<text.length){white();const key=string();rvNeed(!seen.has(key),'JSON含重复字段');seen.add(key);white();i++;visit(depth+1);white();if(text[i++]==='}')break;}}
  else if(ch==='['){white();if(text[i]===']'){i++;return;}while(i<text.length){visit(depth+1);white();if(text[i++]===']')break;}}
  else if(ch==='"'){i--;string();}else{while(i<text.length&&!/[\s,\]}]/.test(text[i]))i++;}
 };visit(0);return value;
}
async function rvHash(text){return [...new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(text)))].map(x=>x.toString(16).padStart(2,'0')).join('');}
function rvGini(values){const total=values.reduce((a,b)=>a+b,0);if(!values.length||!total)return null;return [...values].sort((a,b)=>a-b).reduce((sum,v,i)=>sum+(2*(i+1)-values.length-1)*v,0)/(values.length*total);}
function rvCheckSummary(saved,decisions,tail){
 rvObject(saved,['queries','emptyQueries','selections','opportunities','metrics','meanBaseScore','relevanceRetention','meanDistanceKm']);
 const opportunities={},selections={};let slots=0,score=0,ideal=0,distance=0;
 for(const d of decisions){for(const p of d.eligible){opportunities[p.id]=(opportunities[p.id]??0)+1;selections[p.id]??=0;}ideal+=d.eligible.map(p=>p.baseScore).sort((a,b)=>b-a).slice(0,3).reduce((a,b)=>a+b,0);
  for(const id of d.selected){const p=d.eligible.find(p=>p.id===id);selections[id]++;slots++;score+=p.baseScore;distance+=p.distanceKm;}}
 for(const [key,expected] of Object.entries({opportunities,selections})){const got=saved[key];rvNeed(got&&Object.keys(got).length===Object.keys(expected).length&&Object.keys(expected).every(id=>got[id]===expected[id]),'汇总计数与逐轮记录不一致');}
 const counts=Object.values(selections),size=counts.length,covered=counts.filter(x=>x>0).length;
 const metrics={eligibleNodes:size,selections:slots,gini:rvGini(counts),longTailShare:slots?Object.entries(selections).filter(([id])=>tail.has(Number(id))).reduce((a,[,v])=>a+v,0)/slots:null,coverage:size?covered/size:null,zeroExposureShare:size?(size-covered)/size:null,opportunityAdjustedGini:rvGini(Object.keys(opportunities).map(id=>selections[id]/opportunities[id]))};
 rvObject(saved.metrics,Object.keys(metrics));rvNeed(Object.keys(metrics).every(k=>rvEqual(saved.metrics[k],metrics[k])),'汇总指标与逐轮记录不一致');
 rvNeed(saved.queries===decisions.length&&saved.emptyQueries===decisions.filter(d=>!d.selected.length).length&&rvEqual(saved.meanBaseScore,slots?score/slots:null)&&rvEqual(saved.meanDistanceKm,slots?distance/slots:null)&&rvEqual(saved.relevanceRetention,ideal?score/ideal:null),'汇总查询或评分与逐轮记录不一致');
}
export async function validateResultView(raw){
 const a=rvJson(raw,8_000_000);rvObject(a,['schemaVersion','artifactType','dataVerifiedByTool','realStudyComplete','contextJson','reportJson','hashes','trace']);
 rvNeed(a.schemaVersion===1&&a.artifactType==='OFFLINE_RESULT_VIEW','不是支持的结果查看文件');rvNeed(a.dataVerifiedByTool===false&&a.realStudyComplete===false,'结果必须保留未认证和未完成研究标记');
 rvObject(a.hashes,['contextSha256','reportSha256','inputSha256']);rvNeed(Object.values(a.hashes).length===3&&Object.values(a.hashes).every(x=>typeof x==='string'&&/^[0-9a-f]{64}$/.test(x)),'来源校验和格式不正确');
 rvNeed(await rvHash(a.contextJson)===a.hashes.contextSha256&&await rvHash(a.reportJson)===a.hashes.reportSha256,'上下文或原报告校验和不一致');
 const c=rvJson(a.contextJson,1_000_000),r=rvJson(a.reportJson,5_000_000);
 rvObject(c,['datasetId','datasetKind','coordinateSystem','seed','fairnessWeight','longTailPlaceIds','nodes','queries']);
 rvNeed(rvText(c.datasetId)&&['SYNTHETIC_TEST_ONLY','USER_SUPPLIED'].includes(c.datasetKind)&&['SYNTHETIC','WGS84','GCJ02'].includes(c.coordinateSystem),'数据来源或坐标声明不支持');
 rvNeed(c.coordinateSystem!=='SYNTHETIC'||c.datasetKind==='SYNTHETIC_TEST_ONLY','合成坐标不能作为提交实地数据');rvNeed(Number.isSafeInteger(c.seed)&&rvNum(c.fairnessWeight),'种子须为安全整数，权重须在0–1');
 rvNeed(Array.isArray(c.nodes)&&c.nodes.length>0&&c.nodes.length<=1000&&Array.isArray(c.queries)&&c.queries.length>0&&c.queries.length<=1000&&c.nodes.length*c.queries.length<=50000,'节点或查询数量不符合限制');
 const nodes=new Map(),queries=new Map();
 for(const n of c.nodes){rvObject(n,['id','name','category','ticketPrice','suggestedMinutes']);rvNeed(rvInt(n.id,1)&&!nodes.has(n.id)&&rvText(n.name)&&rvText(n.category)&&rvInt(n.ticketPrice,0,1000)&&rvInt(n.suggestedMinutes,1,1440),'节点字段缺失、重复或非法');nodes.set(n.id,n);}
 for(const q of c.queries){rvObject(q,['id','stratum','localTime','maxDistanceKm','remainingMinutes','maxTicketPrice','states']);rvNeed(rvText(q.id,120)&&!queries.has(q.id)&&rvText(q.stratum,160)&&!['ALL','__proto__','constructor','prototype'].includes(q.stratum),'查询字段或分组重复、非法');rvNeed(typeof q.localTime==='string'&&/^([01]\d|2[0-3]):[0-5]\d$/.test(q.localTime)&&rvNum(q.maxDistanceKm,.5,50)&&rvInt(q.remainingMinutes,1,1440)&&(q.maxTicketPrice===null||rvInt(q.maxTicketPrice,0,1000)),'查询硬约束非法');rvNeed(Array.isArray(q.states)&&new Set(q.states).size===q.states.length&&q.states.every(s=>['TIRED','HUNGRY','WANT_WALK','QUIET','PHOTO','CULTURE','NATURE'].includes(s)),'查询状态不支持');queries.set(q.id,q);}
 rvNeed(Array.isArray(c.longTailPlaceIds)&&new Set(c.longTailPlaceIds).size===c.longTailPlaceIds.length&&c.longTailPlaceIds.every(id=>nodes.has(id)),'长尾列表必须引用已有节点');
 rvObject(r,['version','measurement','datasetId','datasetKind','seed','fairnessWeight','queryOrder','variants']);rvNeed(rvText(r.version)&&r.measurement==='SIMULATED_SELECTIONS_EQUAL_POSITION_WEIGHT'&&r.datasetId===c.datasetId&&r.datasetKind===c.datasetKind&&r.seed===c.seed&&r.fairnessWeight===c.fairnessWeight,'报告来源或条件与上下文不一致');
 rvNeed(Array.isArray(r.queryOrder)&&r.queryOrder.length===queries.size&&new Set(r.queryOrder).size===queries.size&&r.queryOrder.every(id=>queries.has(id)),'查询顺序缺失或重复');
 rvNeed(Array.isArray(r.variants)&&r.variants.length===3&&new Set(r.variants.map(v=>v.strategy)).size===3&&r.variants.every(v=>RV_STRATEGIES.includes(v.strategy)),'三组策略缺失或重复');
 const tail=new Set(c.longTailPlaceIds),strata=[...new Set(c.queries.map(q=>q.stratum))];let reference;
 for(const v of r.variants){
  rvObject(v,['strategy','aggregate','strata','decisions']);rvNeed(Array.isArray(v.decisions)&&v.decisions.length===queries.size&&v.decisions.every((d,i)=>d.queryId===r.queryOrder[i]),'策略的查询记录不完整');
  const feasible=[];
  for(const d of v.decisions){rvObject(d,['queryId','stratum','eligible','selected']);const q=queries.get(d.queryId);rvNeed(d.stratum===q.stratum&&Array.isArray(d.eligible)&&d.eligible.length<=nodes.size,'候选或场景不一致');const seen=new Set();
   for(const p of d.eligible){rvObject(p,['id','baseScore','distanceKm','priority','interest','distanceFactor','qualityFactor','stateFactor','weatherFactor']);const n=nodes.get(p.id);rvNeed(n&&!seen.has(p.id)&&rvNum(p.baseScore)&&rvNum(p.distanceKm,0,q.maxDistanceKm+1e-9)&&rvNum(p.priority,-100,100)&&['interest','distanceFactor','qualityFactor','stateFactor','weatherFactor'].every(k=>rvNum(p[k]))&&n.suggestedMinutes<=q.remainingMinutes&&(q.maxTicketPrice===null||n.ticketPrice<=q.maxTicketPrice),'候选或硬约束与上下文不一致');seen.add(p.id);}
   rvNeed(Array.isArray(d.selected)&&d.selected.length===Math.min(3,d.eligible.length)&&new Set(d.selected).size===d.selected.length&&d.selected.every(id=>seen.has(id)),'入选节点未知、重复或数量不完整');
   feasible.push(d.eligible.map(({priority,...p})=>p));
  }
  if(reference)rvNeed(JSON.stringify(reference)===JSON.stringify(feasible),'三策略可行候选不一致');else reference=feasible;
  rvCheckSummary(v.aggregate,v.decisions,tail);rvNeed(v.strata&&Object.keys(v.strata).length===strata.length&&strata.every(s=>Object.hasOwn(v.strata,s)),'场景汇总缺失');for(const s of strata)rvCheckSummary(v.strata[s],v.decisions.filter(d=>d.stratum===s),tail);
 }
 rvObject(a.trace,['producer','algorithmVersion','buildId','sourceFileCount']);rvNeed(['FROZEN_RUNNER','HISTORICAL_JAVA_EXPORT'].includes(a.trace.producer)&&a.trace.algorithmVersion===r.version&&(a.trace.buildId===null||rvText(a.trace.buildId))&&rvInt(a.trace.sourceFileCount,1,10000),'运行来源声明不完整');
 return {input:c,report:r,hashes:a.hashes,trace:a.trace,kindLabel:c.datasetKind==='SYNTHETIC_TEST_ONLY'?'合成演示 · 非实地结果':'提交数据 · 来源未认证',realStudyComplete:false};
}
export function getResultView(value,scope='ALL',queryId){
 rvNeed(scope==='ALL'||value.input.queries.some(q=>q.stratum===scope),'场景不存在');rvNeed(!queryId||value.input.queries.some(q=>q.id===queryId&&(scope==='ALL'||q.stratum===scope)),'旧查询不属于当前结果');
 return getView(value,scope,queryId);
}
export function resultNodeName(value,node){return value.input.datasetKind==='SYNTHETIC_TEST_ONLY'?'合成文化节点 '+String(node.id).padStart(2,'0'):node.name;}
export function resultBrief(value){
 const r=value.report,c=value.input,format=(v,percent=false)=>v===null?'未定义':(v*(percent?100:1)).toFixed(percent?2:4)+(percent?'%':'');
 const safe=s=>String(s).replace(/[\\`*_{}\[\]()#+.!|<>]/g,'\\$&').replace(/[\r\n]+/g,' ');
 const lines=['# TravelMind 实验答辩摘要','',value.kindLabel,'','本页校验了查看文件中的原报告与上下文摘要；原始材料和原始完整输入未在本页重验。校验和不是数字签名或事实认证。',`数据集：${safe(c.datasetId)}；算法：${safe(r.version)}；种子：${c.seed}；权重：${c.fairnessWeight}。`,`节点 ${c.nodes.length} 个，查询 ${c.queries.length} 次。`,'','| 策略 | 模拟入选Gini | 长尾份额 | 引擎分保留比 | 无结果查询 |','|---|---:|---:|---:|---:|'];
 for(const key of RV_STRATEGIES){const a=r.variants.find(v=>v.strategy===key).aggregate;lines.push(`| ${key} | ${format(a.metrics.gini)} | ${format(a.metrics.longTailShare,true)} | ${format(a.relevanceRetention,true)} | ${a.emptyQueries} |`);}
 const rel=r.variants.find(v=>v.strategy==='RELEVANCE').aggregate.metrics,fair=r.variants.find(v=>v.strategy==='FAIR_EXPOSURE').aggregate.metrics;
 const delta=(a,b,points=false)=>a===null||b===null?'未定义':((a-b)>0?'+':'')+((a-b)*(points?100:1)).toFixed(4)+(points?' 个百分点':'');
 lines.push('',`公平减相关性：Gini差 ${delta(fair.gini,rel.gini)}；长尾份额差 ${delta(fair.longTailShare,rel.longTailShare,true)}。`,'','限制：统计模拟入选，不是真实页面曝光、到访或满意度；引擎评分不是满意度真值。单次结果不能替代多种子或真实用户验证。',c.nodes.length<=3?'仅3个或更少节点，Top3缺乏完整竞争，只能做流程试点。':'某轮可行候选≤3时，不能把全部入选解释为公平重排的额外收益。','提交类型、材料许可与真实研究状态需人工核实；未将假设验证改写为已完成研究。','',`原始报告SHA-256：${value.hashes.reportSha256}`,`原始输入SHA-256（来源声明）：${value.hashes.inputSha256}`);
 return lines.join('\n')+'\n';
}
