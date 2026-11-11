import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const lab=await import('./lab-model.mjs').catch(e=>{if(e.code==='ERR_MODULE_NOT_FOUND')return {};throw e;});
const base=JSON.parse(fs.readFileSync(new URL('./fixtures/baseline/input.json',import.meta.url),'utf8'));
const options={maxDistanceKm:.5,remainingMinutes:60,fairnessWeight:.35,seed:20261102};
const build=x=>{assert.equal(typeof lab.createLabInput,'function','缺少实时实验参数适配');return lab.createLabInput(base,x);};
test('同一18条需求统一覆盖距离和时间，保留完整画像与状态',()=>{
 const input=build(options);assert.equal(input.queries.length,18);
 for(let i=0;i<18;i++){assert.equal(input.queries[i].maxDistanceKm,.5);assert.equal(input.queries[i].remainingMinutes,60);assert.deepEqual(input.queries[i].preference,base.queries[i].preference);assert.deepEqual(input.queries[i].states,base.queries[i].states);}
 assert.deepEqual(input.nodes,base.nodes);assert.equal(input.datasetKind,'SYNTHETIC_TEST_ONLY');
});
test('适配不修改原始输入，重复配置得到完全相同输入',()=>{const before=JSON.stringify(base);assert.deepEqual(build(options),build(options));assert.equal(JSON.stringify(base),before);});
test('拒绝未知参数、字符串、布尔值和空配置',()=>{build(options);for(const x of [null,{}, {...options,seed:'1'}, {...options,maxDistanceKm:true},{...options,command:'java'}])assert.throws(()=>build(x));});
test('严格执行距离、时间、权重和种子的数值范围',()=>{build(options);for(const [k,v] of [['maxDistanceKm',.49],['maxDistanceKm',51],['remainingMinutes',0],['remainingMinutes',1441],['remainingMinutes',30.5],['fairnessWeight',1.01],['seed',-1],['seed',2147483648],['seed',1.5]])assert.throws(()=>build({...options,[k]:v}));});
test('不允许替换为真实地点或未标明的坐标系',()=>{build(options);for(const changed of [{...base,datasetKind:'USER_SUPPLIED'},{...base,coordinateSystem:'GCJ02'}])assert.throws(()=>lab.createLabInput(changed,options));});
