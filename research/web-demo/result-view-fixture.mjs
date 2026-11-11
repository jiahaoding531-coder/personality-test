import {fileURLToPath} from 'node:url';
// 仅测试用：读取已存在的原Java合成报告，不创建真实调查数据。
import fs from 'node:fs';
import crypto from 'node:crypto';
const base=fileURLToPath(new URL('./fixtures/baseline/',import.meta.url));
const empty=fileURLToPath(new URL('./fixtures/empty/',import.meta.url));
export const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
export function resultFixture({emptyResult=false,kind='SYNTHETIC_TEST_ONLY'}={}){
 const input=JSON.parse(fs.readFileSync(emptyResult?empty+'time-restricted.json':base+'input.json','utf8'));
 const report=JSON.parse(fs.readFileSync(emptyResult?empty+'time-restricted/report.json':base+'report.json','utf8'));
 input.datasetKind=kind;report.datasetKind=kind;
 // USER_SUPPLIED分支也只是契约测试，坐标不导出、不构成真实地点验证。
 if(kind==='USER_SUPPLIED')input.coordinateSystem='GCJ02';
 const context={};for(const k of ['datasetId','datasetKind','coordinateSystem','seed','fairnessWeight','longTailPlaceIds'])context[k]=input[k];
 context.nodes=input.nodes.map(n=>Object.fromEntries(['id','name','category','ticketPrice','suggestedMinutes'].map(k=>[k,n[k]])));
 context.queries=input.queries.map(q=>Object.fromEntries(['id','stratum','localTime','maxDistanceKm','remainingMinutes','maxTicketPrice','states'].map(k=>[k,q[k]])));
 const a={schemaVersion:1,artifactType:'OFFLINE_RESULT_VIEW',dataVerifiedByTool:false,realStudyComplete:false,contextJson:JSON.stringify(context),reportJson:JSON.stringify(report),hashes:{inputSha256:hash(JSON.stringify(input))},trace:{producer:'HISTORICAL_JAVA_EXPORT',algorithmVersion:report.version,buildId:null,sourceFileCount:1}};
 return reseal(a);
}
export function reseal(a){a.hashes.contextSha256=hash(a.contextJson);a.hashes.reportSha256=hash(a.reportJson);return a;}
