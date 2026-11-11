import {digest} from './lab-build.mjs';
// 只核对原始文件与该次构建的追溯关系，不重新过滤、评分或计算公平指标。
export function validateLabArtifacts({inputBytes,reportBytes,manifest,buildManifest}){
 if(manifest.inputSha256!==digest(inputBytes)||manifest.reportSha256!==digest(reportBytes))throw Error('输入或报告校验和不一致。');
 const same=(a,b)=>a&&b&&Object.keys(a).length===Object.keys(b).length&&Object.keys(a).every(k=>a[k]===b[k]);
 if(!same(manifest.sourceSha256,buildManifest.sourceSha256))throw Error('输出源码记录与该次构建不一致。');
 const classes={};
 for(const [key,value] of Object.entries(manifest.runtimeClassSha256??{})){
  if(!/^(main|test)\//.test(key))throw Error('运行字节码记录格式不正确。');
  const name=key.replace(/^(main|test)\//,'');if(Object.hasOwn(classes,name))throw Error('运行字节码重复。');classes[name]=value;
 }
 if(!same(classes,buildManifest.runtimeClassSha256))throw Error('输出字节码与该次构建不一致。');
 if(manifest.dataVerifiedByTool!==false||manifest.experimentAuthorized===true)throw Error('输出不能授予事实核验或实验资格。');
 const input=JSON.parse(inputBytes),report=JSON.parse(reportBytes);
 if(input.datasetKind!=='SYNTHETIC_TEST_ONLY'||report.datasetKind!==input.datasetKind||report.datasetId!==input.datasetId||report.measurement!=='SIMULATED_SELECTIONS_EQUAL_POSITION_WEIGHT'||report.seed!==input.seed||report.fairnessWeight!==input.fairnessWeight)throw Error('报告来源、批次条件或测量口径不一致。');
 return report;
}
