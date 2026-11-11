// 只检查本地草稿的结构与引用，不认证事实，也不改变正式地点或实验输入。
export class FieldworkValidationError extends Error {
  constructor(message){super(message);this.name='FieldworkValidationError';}
}
export function parseFieldworkReport(text,nodeIds){
  const fail=message=>{throw new FieldworkValidationError(message);};
  if(typeof text!=='string'||text.length>1048576||new Blob([text]).size>1048576)fail('文件过大，请选择不超过 1 MB 的预检结果 JSON。');
  let report;try{report=JSON.parse(text);}catch{fail('文件不是有效 JSON，请选择 fieldwork-review.json。');}
  const object=x=>x!==null&&typeof x==='object'&&!Array.isArray(x);
  if(!object(report)||report.schemaVersion!==1||report.artifactType!=='FIELDWORK_IMPORT_DRAFT')fail('文件版本或类型不受支持，请选择采集工具的预检结果。');
  if(report.dataVerifiedByTool!==false||report.experimentAuthorized!==false)fail('资格标记不符合待核验草稿约定。');
  // 未知扩展不能绕过大小、深度、标记和原型字段检查。
  const stack=[[report,0]];let objects=0;
  while(stack.length){
    const [value,depth]=stack.pop();if(depth>20||++objects>15000)fail('文件结构过深或内容过多。');
    if(typeof value==='string'&&value.length>4000)fail('单项文字过长，请缩短原始记录。');
    if(value&&typeof value==='object')for(const [key,v] of Object.entries(value)){
      if(['__proto__','constructor','prototype'].includes(key))fail('文件包含不支持的结构字段。');
      if(['verified','adopted','experimentEligible','experimentAuthorized','dataVerifiedByTool'].includes(key)&&v!==false)fail('资格标记不符合待核验草稿约定。');
      stack.push([v,depth+1]);
    }
  }
  const fields=['materials','points','facts','tasks','reviews'];
  for(const key of [...fields,'issues']){
    if(!Array.isArray(report[key]))fail('文件缺少记录数组。');
    if(report[key].length>500)fail('文件中的记录过多。');
    if(!report[key].every(object))fail('记录格式不正确。');
  }
  if(!object(report.counts)||fields.some(k=>report.counts[k]!==report[k].length))fail('声明数量与实际记录数量不一致。');
  if(!object(report.inputSha256)||['workbook','register'].some(k=>typeof report.inputSha256[k]!=='string'||!/^[a-f0-9]{64}$/.test(report.inputSha256[k])))fail('输入校验和格式不正确。');
  const ids=new Set(nodeIds);if(ids.size!==nodeIds.length)fail('页面节点登记不完整。');
  const optionalText=v=>v===null||typeof v==='string';
  const line=(r,sheet)=>{if(typeof r.workbookRow!=='string'||!new RegExp('^'+sheet+'!\\d+$').test(r.workbookRow))fail('记录缺少合法的工作表位置。');};
  const dates=(v)=>v===null||(typeof v==='string'&&/^\d{4}-\d{2}-\d{2}$/.test(v)&&Number.isFinite(Date.parse(v))&&new Date(v).toISOString().slice(0,10)===v);
  const times=v=>v===null||(typeof v==='string'&&/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?[+-]\d{2}:\d{2}$/.test(v)&&dates(v.slice(0,10))&&Number.isFinite(Date.parse(v)));
  const number=v=>v===null||(typeof v==='number'&&Number.isFinite(v));
  const shape=(r,strings,dateFields=[],timeFields=[])=>{
    if(strings.some(k=>!optionalText(r[k]))||dateFields.some(k=>!dates(r[k]))||timeFields.some(k=>!times(r[k])))fail('记录中的文字或日期格式不正确。');
  };
  const materialIds=new Set();
  for(const r of report.materials){
    if(typeof r.id!=='string'||!/^[A-Za-z0-9_-]{1,80}$/.test(r.id))fail('材料编号格式不正确。');
    if(materialIds.has(r.id))fail('材料编号重复。');materialIds.add(r.id);
    line(r,'来源登记');shape(r,['scope','title','location','description','reuseScope','reviewStatement'],['publicationDate','collectedOn']);
    if(!r.title||!r.location||!r.collectedOn||r.verified!==false)fail('材料缺少必要字段或待核验标记。');
  }
  const node=r=>{if(!ids.has(r.nodeId))fail('记录引用了不属于本页的节点。');};
  const material=r=>{if(r.materialId!==null&&!materialIds.has(r.materialId))fail('记录引用了不存在的材料。');};
  const pointIds=new Set();
  for(const r of report.points){
    node(r);material(r);line(r,'入口坐标');shape(r,['pointId','description','method','recordReference'],[],['collectedAt']);
    if(![null,'PUBLIC_ENTRANCE','STREET_ENDPOINT','STREET_REPRESENTATIVE_POINT','CAMERA_LOCATION','OTHER'].includes(r.role)||!['UNKNOWN','WGS84','GCJ02'].includes(r.coordinateSystem))fail('点位角色或坐标系不受支持。');
    if(!number(r.latitude)||!number(r.longitude)||(r.latitude===null)!==(r.longitude===null)||r.latitude!==null&&(Math.abs(r.latitude)>90||Math.abs(r.longitude)>180)||!number(r.accuracyMeters)||r.accuracyMeters!==null&&r.accuracyMeters<0)fail('点位数值格式或范围不正确。');
    if(r.pointId!==null){if(pointIds.has(r.pointId))fail('点位编号重复。');pointIds.add(r.pointId);}
    if(r.verified!==false||r.adopted!==false)fail('点位资格标记不符合草稿约定。');
  }
  for(const r of report.facts){
    if(r.scope==='ALL_NODES'){if(r.nodeId!==null)fail('全局记录不能同时声明单个节点。');}else{if(r.scope!=='NODE')fail('现场记录范围不正确。');node(r);}
    material(r);line(r,'现场事实');shape(r,['topic','observation','applicability','pageReference','investigatorCode','uncertainty'],[],['observedAt']);
    if(!number(r.numericValue)||r.numericValue!==null&&r.numericValue<0||r.verified!==false)fail('现场数值或待核验标记不正确。');
  }
  for(const r of report.tasks){
    node(r);material(r);line(r,'采集任务');shape(r,['topic','investigatorCode','reason'],['collectedOn']);
    if(!['未采集','未确认','有冲突','不适用','已提交复核'].includes(r.status))fail('采集状态不受支持。');
  }
  for(const r of report.reviews){
    node(r);line(r,'人工复核');shape(r,['topic','materialReference','reviewerCode','reason','nextAction'],['reviewDate']);
    if(!['未复核','缺证','有冲突','需补查','材料已复核','不适用'].includes(r.status))fail('复核声明状态不受支持。');
  }
  for(const r of report.issues){
    if(!['ERROR','GAP','REVIEW'].includes(r.severity)||['code','location','message'].some(k=>typeof r[k]!=='string'))fail('待补提示格式不正确。');
  }
  // 输出采用白名单。原始坐标、相机精度和本地材料路径不进入默认展示模型。
  const fact=r=>({topic:r.topic,observation:r.observation,numericValue:r.numericValue,applicability:r.applicability,materialId:r.materialId,uncertainty:r.uncertainty,workbookRow:r.workbookRow});
  return {
    counts:Object.fromEntries(fields.map(k=>[k,report[k].length])),dataVerifiedByTool:false,experimentAuthorized:false,
    declaredInputHashes:{...report.inputSha256},
    materials:report.materials.map(r=>({id:r.id,title:r.title,scope:r.scope,collectedOn:r.collectedOn})),
    nodes:nodeIds.map(nodeId=>({nodeId,
      points:report.points.filter(r=>r.nodeId===nodeId).map(r=>({pointId:r.pointId,role:r.role,description:r.description,coordinateSystem:r.coordinateSystem,materialId:r.materialId,workbookRow:r.workbookRow})),
      facts:report.facts.filter(r=>r.nodeId===nodeId).map(fact),
      tasks:report.tasks.filter(r=>r.nodeId===nodeId).map(r=>({topic:r.topic,status:r.status,reason:r.reason,materialId:r.materialId})),
      reviews:report.reviews.filter(r=>r.nodeId===nodeId).map(r=>({topic:r.topic,status:r.status,reason:r.reason,reviewDate:r.reviewDate}))
    })),
    globalFacts:report.facts.filter(r=>r.scope==='ALL_NODES').map(fact),
    issues:report.issues.map(r=>({severity:r.severity,location:r.location,message:r.message}))
  };
}
