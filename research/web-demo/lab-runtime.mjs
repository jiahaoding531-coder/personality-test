import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import {labRoot,repositoryRoot,javaHome,javaExecutable,javacExecutable,digest,sourceHashes,classHashes,executeFile} from './lab-build.mjs';
import {createLabInput} from './lab-model.mjs';
import {validateLabArtifacts} from './lab-output.mjs';
async function verifiedBuild(){
 const cache=path.join(labRoot,'lab-cache'),current=JSON.parse(await fs.readFile(path.join(cache,'current.json'),'utf8'));
 if(typeof current.buildId!=='string'||!/^[a-f0-9-]{36}$/.test(current.buildId))throw Error('独立构建缺失。');
 const build=path.join(cache,'builds',current.buildId),manifest=JSON.parse(await fs.readFile(path.join(build,'build.json'),'utf8')),classes=path.join(build,'classes');
 if(JSON.stringify(await sourceHashes())!==JSON.stringify(manifest.sourceSha256)||JSON.stringify(await classHashes(classes))!==JSON.stringify(manifest.runtimeClassSha256))throw Error('源码或字节码已变化，请重新构建。');
 const bytes=await fs.readFile(path.join(build,'baseline.json')),deps=(await fs.readFile(path.join(repositoryRoot,'backend/target/offline-fairness-classpath.txt'),'utf8')).trim();
 if(digest(bytes)!==manifest.baselineSha256||digest(deps)!==manifest.dependenciesSha256)throw Error('模板或依赖记录已变化，请重新构建。');
 return {classes,manifest,deps,baseline:JSON.parse(bytes)};
}
export async function inspectLabEnvironment(){
 const build=await verifiedBuild();await executeFile(javaExecutable,['-version'],{timeout:5000,windowsHide:true});await executeFile(javacExecutable,['-version'],{timeout:5000,windowsHide:true});
 return {buildId:build.manifest.buildId,sourceFileCount:Object.keys(build.manifest.sourceSha256).length,classCount:Object.keys(build.manifest.runtimeClassSha256).length,datasetKind:build.baseline.datasetKind,nodeCount:build.baseline.nodes.length,queryCount:build.baseline.queries.length};
}
export async function executeJavaExperiment(input){
 const build=await verifiedBuild();
 // HTTP只能从固定模板适配。底层测试入口也不接受外部真实地点、增加查询或替换画像。
 const allowed=structuredClone(build.baseline);allowed.seed=input.seed;allowed.fairnessWeight=input.fairnessWeight;
 allowed.queries=allowed.queries.map((q,i)=>({...q,maxDistanceKm:input.queries?.[i]?.maxDistanceKm,remainingMinutes:input.queries?.[i]?.remainingMinutes}));
 if(JSON.stringify(input)!==JSON.stringify(allowed))throw Error('只允许修改固定合成实验的已授权参数。');
 const runs=path.join(labRoot,'lab-runs');await fs.mkdir(runs,{recursive:true});
 if((await fs.readdir(runs)).length>=100)throw Error('本机实验目录已达100个，请人工归档后再运行。');
 const runId=crypto.randomUUID(),dir=path.join(runs,runId);await fs.mkdir(dir);
 const bytes=Buffer.from(JSON.stringify(input));await fs.writeFile(path.join(dir,'input.json'),bytes,{flag:'wx'});
 await executeFile(javaExecutable,['-Xmx128m','-Dfile.encoding=UTF-8','-cp',build.classes+path.delimiter+build.deps,'com.example.personality.experiment.OfflineExperimentMain',path.join(dir,'input.json'),path.join(dir,'output'),repositoryRoot],{timeout:10000,maxBuffer:16384,windowsHide:true});
 const reportBytes=await fs.readFile(path.join(dir,'output/report.json')),manifest=JSON.parse(await fs.readFile(path.join(dir,'output/manifest.json'),'utf8'));
 const report=validateLabArtifacts({inputBytes:bytes,reportBytes,manifest,buildManifest:build.manifest});
 const trace={runId,inputSha256:manifest.inputSha256,reportSha256:manifest.reportSha256,buildId:build.manifest.buildId,javaVersion:manifest.javaVersion,sourceSha256:manifest.sourceSha256,runtimeClassSha256:manifest.runtimeClassSha256,dataVerifiedByTool:false,paidApiCalls:0,databaseWrites:0};
 return {input,report,trace};
}
export async function runLab(options){const build=await verifiedBuild();return executeJavaExperiment(createLabInput(build.baseline,options));}
