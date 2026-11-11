import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
const output=await import('./lab-output.mjs').catch(e=>{if(e.code==='ERR_MODULE_NOT_FOUND')return {};throw e;});
const hash=x=>crypto.createHash('sha256').update(x).digest('hex');
function fixture(){
 const input={datasetId:'SYNTHETIC_TEST_ONLY',datasetKind:'SYNTHETIC_TEST_ONLY',seed:1,fairnessWeight:.35};
 const report={datasetId:input.datasetId,datasetKind:input.datasetKind,seed:1,fairnessWeight:.35,measurement:'SIMULATED_SELECTIONS_EQUAL_POSITION_WEIGHT'};
 const inputBytes=Buffer.from(JSON.stringify(input)),reportBytes=Buffer.from(JSON.stringify(report)),buildManifest={sourceSha256:{'source.java':'a'.repeat(64)},runtimeClassSha256:{'example.class':'b'.repeat(64)}};
 const manifest={inputSha256:hash(inputBytes),reportSha256:hash(reportBytes),sourceSha256:{...buildManifest.sourceSha256},runtimeClassSha256:{'main/example.class':'b'.repeat(64)},dataVerifiedByTool:false};
 return {inputBytes,reportBytes,manifest,buildManifest};
}
function validate(x){assert.equal(typeof output.validateLabArtifacts,'function','缺少结束时构建绑定校验');return output.validateLabArtifacts(x);}
test('结束时校验接受与该次构建和输入一致的原始输出',()=>assert.equal(validate(fixture()).datasetKind,'SYNTHETIC_TEST_ONLY'));
test('执行期间源码记录变化不能当成当前构建输出',()=>{validate(fixture());const f=fixture();f.manifest.sourceSha256['source.java']='c'.repeat(64);assert.throws(()=>validate(f),/源码|构建/);});
test('运行字节码摘要不同、缺少或重复均拒绝',()=>{validate(fixture());for(const mode of ['changed','missing','duplicate']){const f=fixture();if(mode==='changed')f.manifest.runtimeClassSha256['main/example.class']='c'.repeat(64);if(mode==='missing')f.manifest.runtimeClassSha256={};if(mode==='duplicate')f.manifest.runtimeClassSha256['test/example.class']='b'.repeat(64);assert.throws(()=>validate(f),/字节码|构建/);}});
test('输入或报告原始字节变化不能靠旧摘要通过',()=>{for(const key of ['inputBytes','reportBytes']){const f=fixture();f[key]=Buffer.from('{}');assert.throws(()=>validate(f),/校验和|输入/);}});
test('报告批次、数据集或测量口径不一致时拒绝',()=>{validate(fixture());for(const [key,value] of [['seed',2],['fairnessWeight',.7],['datasetId','OTHER'],['measurement','REAL_EXPOSURE']]){const f=fixture(),r=JSON.parse(f.reportBytes);r[key]=value;f.reportBytes=Buffer.from(JSON.stringify(r));f.manifest.reportSha256=hash(f.reportBytes);assert.throws(()=>validate(f),/一致|口径/);}});
test('外部输出声明资料已核实也不能授予资格',()=>{const f=fixture();f.manifest.dataVerifiedByTool=true;assert.throws(()=>validate(f),/资格|核验/);});
