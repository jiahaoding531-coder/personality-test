import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {inspectLabEnvironment} from './lab-runtime.mjs';
import {digest} from './lab-build.mjs';
const root=path.dirname(fileURLToPath(import.meta.url));
const checks={schemaVersion:1,checkedAt:new Date().toISOString(),javaBuildReady:false,webBuildReady:false,localServerReady:false,paidApiCalls:0,databaseWrites:0,productionChecked:false,realEvidenceVerified:false,realStudyComplete:false};
const issues=[];
try{checks.javaBuild=await inspectLabEnvironment();checks.javaBuildReady=true;}catch{issues.push('JAVA_BUILD_MISSING_OR_CHANGED');}
let expectedHash;
try{
 const manifest=JSON.parse(await fs.readFile(path.join(root,'manifest.json'),'utf8'));
 const sources=['page.html','demo-model.mjs','robustness-ui.js','fieldwork-model.mjs','fieldwork-ui.js','lab-ui.js','result-view-model.mjs','result-view-ui.js','ranking-evidence.mjs','ranking-evidence-ui.js'];
 if(Object.keys(manifest.webSourceSha256??{}).length!==sources.length)throw Error('网页构建来源缺失');
 for(const name of sources)if(manifest.webSourceSha256[name]!==digest(await fs.readFile(path.join(root,name))))throw Error('网页源码变化');
 expectedHash=manifest.htmlSha256;if(expectedHash!==digest(await fs.readFile(path.join(root,'index.html'))))throw Error('网页产物变化');
 checks.webBuildReady=true;checks.htmlSha256=expectedHash;
}catch{issues.push('WEB_BUILD_MISSING_OR_STALE');}
// 只重试无副作用的本机首页读取。绝不触发实验、高德、数据库或模型请求。
for(let attempt=0;attempt<3;attempt++){
 try{
  const response=await fetch('http://127.0.0.1:5186/',{signal:AbortSignal.timeout(1500),redirect:'error'});
  const bytes=Buffer.from(await response.arrayBuffer());
  if(response.status===200&&expectedHash&&digest(bytes)===expectedHash){checks.localServerReady=true;break;}
 }catch{/* 启动中的本机服务可能尚未监听，健康读取可有界重试。 */}
 if(attempt<2)await new Promise(resolve=>setTimeout(resolve,200));
}
if(!checks.localServerReady)issues.push('LOCAL_SERVER_UNAVAILABLE_OR_STALE');
checks.softwareReadyForLocalDemo=checks.javaBuildReady&&checks.webBuildReady&&checks.localServerReady;
checks.issues=issues;
const output=path.join(root,'local-checks',crypto.randomUUID());await fs.mkdir(output,{recursive:true});
await fs.writeFile(path.join(output,'report.json'),JSON.stringify(checks,null,2),{flag:'wx'});
console.log(checks.softwareReadyForLocalDemo?'本地演示环境自检通过。':'本地演示环境自检未通过，请核对构建与本机服务版本。');
console.log(`Java构建 ${checks.javaBuildReady?'通过':'待修复'}；网页构建 ${checks.webBuildReady?'通过':'待修复'}；本机服务 ${checks.localServerReady?'通过':'待修复'}。`);
console.log('真实地点资料尚未在系统中核验，研究项目不标记完成。未检查生产环境。');
if(issues.length)console.log('检查代码：'+issues.join(', '));
console.log(path.join(output,'report.json'));
process.exitCode=checks.softwareReadyForLocalDemo?0:1;
