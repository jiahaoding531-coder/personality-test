import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import crypto from 'node:crypto';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
export const labRoot=path.dirname(fileURLToPath(import.meta.url));
export const repositoryRoot=path.resolve(labRoot,'../..');
export const javaHome=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin'):'';
export const javaExecutable=javaHome?path.join(javaHome,process.platform==='win32'?'java.exe':'java'):(process.platform==='win32'?'java.exe':'java');
export const javacExecutable=javaHome?path.join(javaHome,process.platform==='win32'?'javac.exe':'javac'):(process.platform==='win32'?'javac.exe':'javac');
export const digest=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
export const executeFile=promisify(execFile);
export async function sourceHashes(){
 const names=['backend/pom.xml','backend/src/main/java/com/example/personality/service/RecommendationEngine.java'];
 for(const dir of ['backend/src/main/java/com/example/personality/domain','backend/src/test/java/com/example/personality/experiment'])
  for(const name of (await fs.readdir(path.join(repositoryRoot,dir))).sort())if(name.endsWith('.java'))names.push(dir+'/'+name);
 return Object.fromEntries(await Promise.all(names.map(async name=>[name,digest(await fs.readFile(path.join(repositoryRoot,name)))])));
}
export async function classHashes(root){
 const rows=[];
 async function walk(dir){for(const name of (await fs.readdir(dir,{withFileTypes:true})).sort((a,b)=>a.name.localeCompare(b.name))){const full=path.join(dir,name.name);if(name.isDirectory())await walk(full);else if(name.name.endsWith('.class'))rows.push([path.relative(root,full).replaceAll('\\','/'),digest(await fs.readFile(full))]);}}
 await walk(root);return Object.fromEntries(rows);
}
export async function buildLab(){
 const cache=path.join(labRoot,'lab-cache'),id=crypto.randomUUID(),build=path.join(cache,'builds',id),classes=path.join(build,'classes');
 await fs.mkdir(classes,{recursive:true});
 const before=await sourceHashes(),deps=(await fs.readFile(path.join(repositoryRoot,'backend/target/offline-fairness-classpath.txt'),'utf8')).trim();
 // javac自动编译实际引用的domain类，输出只写入本工具目录。依赖只使用本机缓存，不执行Maven或下载。
 const main=path.join(repositoryRoot,'backend/src/main/java'),tests=path.join(repositoryRoot,'backend/src/test/java');
 const sources=['OfflineExperimentInput','OfflineRanking','OfflineExposureMetrics','OfflineFairnessExperiment','OfflineExperimentMain'].map(n=>path.join(tests,'com/example/personality/experiment',n+'.java'));
 await executeFile(javacExecutable,['-encoding','UTF-8','--release','17','-cp',deps,'-sourcepath',main+path.delimiter+tests,'-d',classes,path.join(main,'com/example/personality/service/RecommendationEngine.java'),...sources],{timeout:30000,maxBuffer:1048576,windowsHide:true});
 const after=await sourceHashes();if(JSON.stringify(before)!==JSON.stringify(after))throw Error('编译期间源码变化，请重新构建。');
 const baseline=await fs.readFile(path.join(labRoot,'fixtures/baseline/input.json'));
 const data=JSON.parse(baseline);if(data.datasetKind!=='SYNTHETIC_TEST_ONLY'||data.nodes.length!==6||data.queries.length!==18)throw Error('不是固定合成样例。');
 await fs.writeFile(path.join(build,'baseline.json'),baseline,{flag:'wx'});
 const manifest={schemaVersion:1,buildId:id,sourceSha256:before,runtimeClassSha256:await classHashes(classes),baselineSha256:digest(baseline),dependenciesSha256:digest(deps)};
 await fs.writeFile(path.join(build,'build.json'),JSON.stringify(manifest,null,2),{flag:'wx'});
 await fs.writeFile(path.join(cache,'current.json'),JSON.stringify({buildId:id}));
 console.log(`独立Java编译通过：${Object.keys(manifest.runtimeClassSha256).length}个字节码文件。原工程未写入。`);
}
if(process.argv[1]&&pathToFileURL(path.resolve(process.argv[1])).href===import.meta.url)await buildLab();
