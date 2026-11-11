// 文件只在当前页面内存中读取；不上传、不持久化、不改正式登记或实验状态。
const fieldworkInput=$('fieldwork-file'),fieldworkResult=$('fieldwork-result');
let fieldworkReadVersion=0;
function fieldworkElement(tag,text,className){
  const el=document.createElement(tag);if(text!==undefined)el.textContent=text;if(className)el.className=className;return el;
}
function clearFieldworkView(){fieldworkResult.replaceChildren();fieldworkResult.hidden=true;$('fieldwork-error').hidden=true;$('fieldwork-error').textContent='';}
function renderFieldwork(view){
  const content=document.createDocumentFragment();
  content.append(fieldworkElement('h3','已导入原始记录 未授予实验资格'));
  content.append(fieldworkElement('p',`材料登记 ${view.counts.materials} 条（包含历史参考）；原始点位 ${view.counts.points} 条；现场记录 ${view.counts.facts} 条；任务状态 ${view.counts.tasks} 条；复核声明 ${view.counts.reviews} 条。`));
  content.append(fieldworkElement('p','格式与引用检查通过不代表事实已核实。以下均为文件内原始声明，未采用为正式坐标、票价、营业状态或属性，未参与上面的合成实验。','notice'));
  const roles={PUBLIC_ENTRANCE:'公共入口',STREET_ENDPOINT:'街区端点',STREET_REPRESENTATIVE_POINT:'街区代表点',CAMERA_LOCATION:'相机位置',OTHER:'其他点位'};
  const factLines=record=>`${record.topic??'主题未知'}：${record.observation??'未填写观察'}；原始数值 ${record.numericValue??'未知'}（单位依采集主题，未采用）；适用范围 ${record.applicability??'未知'}；材料 ${record.materialId??'待补'}${record.uncertainty?'；未确认 '+record.uncertainty:''}`;
  const list=(target,title,lines)=>{
    target.append(fieldworkElement('h4',title));if(!lines.length){target.append(fieldworkElement('p','暂无记录','notice'));return;}
    const ul=fieldworkElement('ul');for(const line of lines)ul.append(fieldworkElement('li',line));target.append(ul);
  };
  const grid=fieldworkElement('div',undefined,'evidence-grid');
  for(const node of view.nodes){
    const card=fieldworkElement('article',undefined,'evidence-card');
    card.append(fieldworkElement('p',node.nodeId,'node-code'));card.append(fieldworkElement('h3',data.readiness.nodes.find(n=>n.nodeId===node.nodeId).name));
    card.append(fieldworkElement('span','原始记录待核验','pill'));
    list(card,'点位记录',node.points.map(r=>`${r.pointId??'编号待补'} · ${roles[r.role]??'角色未知'} · ${r.coordinateSystem} · ${r.description??'描述待补'} · 材料 ${r.materialId??'待补'}；未采用为正式位置`));
    list(card,'现场记录',node.facts.map(factLines));
    list(card,'任务状态声明',node.tasks.map(r=>`${r.topic??'主题待补'} · ${r.status}${r.reason?' · '+r.reason:''}`));
    list(card,'复核声明',node.reviews.map(r=>`${r.topic??'主题待补'} · ${r.status} · 日期 ${r.reviewDate??'未知'}${r.reason?' · '+r.reason:''}；资格仍待另审`));
    grid.append(card);
  }
  content.append(grid);
  if(view.globalFacts.length)list(content,'全部节点记录 不分配给单一地点',view.globalFacts.map(factLines));
  const materials=fieldworkElement('details',undefined,'details');materials.append(fieldworkElement('summary','查看材料登记与待补提示'));
  list(materials,'材料登记 含历史参考',view.materials.map(r=>`${r.id} · ${r.title} · ${r.scope??'范围未知'} · 查阅或采集日期 ${r.collectedOn}`));
  list(materials,'文件内的待补提示',view.issues.map(r=>`${r.severity} · ${r.location} · ${r.message}`));content.append(materials);
  const hashes=fieldworkElement('details',undefined,'details');hashes.append(fieldworkElement('summary','查看文件声明的输入校验和'));
  hashes.append(fieldworkElement('p','以下值来自导入文件。页面未读取原始 Excel、登记基线或 manifest，不能据此认证真实性或文件完整性。','notice'));
  hashes.append(fieldworkElement('p','工作簿 '+view.declaredInputHashes.workbook,'fieldwork-hash'),fieldworkElement('p','基线登记 '+view.declaredInputHashes.register,'fieldwork-hash'));content.append(hashes);
  fieldworkResult.replaceChildren(content);fieldworkResult.hidden=false;
}
fieldworkInput.addEventListener('change',async()=>{
  const file=fieldworkInput.files?.[0];if(!file)return;
  const version=++fieldworkReadVersion;clearFieldworkView();$('fieldwork-clear').disabled=false;$('fieldwork-status').textContent='正在读取本地文件…';
  // 清空原生选择值，失败后可以再次选择相同文件。
  fieldworkInput.value='';
  try{
    if(file.size>1048576)throw new FieldworkValidationError('文件过大，请选择不超过 1 MB 的预检结果 JSON。');
    const text=await file.text();if(version!==fieldworkReadVersion)return;
    renderFieldwork(parseFieldworkReport(text,data.readiness.nodes.map(n=>n.nodeId)));
    $('fieldwork-status').textContent='读取完成。仅保留在当前页面，刷新或清空后移除。';
  }catch(error){
    if(version!==fieldworkReadVersion)return;clearFieldworkView();$('fieldwork-status').textContent='没有采用该文件。';
    $('fieldwork-error').textContent=error instanceof FieldworkValidationError?error.message:'本地文件读取失败，请重新选择采集工具生成的 JSON。';$('fieldwork-error').hidden=false;
  }
});
$('fieldwork-clear').addEventListener('click',()=>{++fieldworkReadVersion;clearFieldworkView();fieldworkInput.value='';$('fieldwork-clear').disabled=true;$('fieldwork-status').textContent='本页导入记录已清空。原始文件未修改。';});
