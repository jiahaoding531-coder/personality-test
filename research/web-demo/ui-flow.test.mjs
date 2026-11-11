import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import fs from 'node:fs';
const require=createRequire(new URL('../../frontend/package.json',import.meta.url));
const {JSDOM,VirtualConsole}=require('jsdom');
const html=fs.readFileSync(new URL('./index.html',import.meta.url),'utf8');
function run(check){
  const errors=[],console=new VirtualConsole();
  console.on('jsdomError',e=>errors.push(e.message));
  // 仅执行本地自有页面，不启用外部资源加载；不把DOM模拟当作真机测试。
  const dom=new JSDOM(html,{runScripts:'dangerously',url:'http://127.0.0.1:5186/',virtualConsole:console});
  const d=dom.window.document;
  const set=(id,value)=>{d.getElementById(id).value=value;d.getElementById(id).dispatchEvent(new dom.window.Event('change'));};
  try{check(d,set);assert.deepEqual(errors,[]);}finally{dom.window.close();}
}
test('实际页面切换种子保持汇总，回放种子随之改变',()=>run((d,set)=>{
  const before=d.getElementById('rob-summaries').textContent;
  set('rob-seed','2026102610');
  assert.equal(d.getElementById('rob-summaries').textContent,before);
  assert.match(d.getElementById('rob-context').textContent,/2026102610/);
}));
test('快速切换权重和场景仅展示最后一次选择且查询数正确',()=>run((d,set)=>{
  for(const w of ['0','1','0.6','0.35']) set('rob-weight',w);
  for(const s of ['合成近距离场景','ALL','合成宽松场景']) set('rob-scope',s);
  assert.equal(d.getElementById('rob-query').options.length,12);
  assert.match(d.getElementById('rob-summary-caption').textContent,/合成宽松场景 · λ = 0.35/);
  assert.equal(d.getElementById('rob-error').hidden,true);
}));
test('真实节点与单种子页签不被多种子状态污染',()=>run((d,set)=>{
  const before=d.getElementById('strategies').textContent,evidence=d.getElementById('evidence').textContent;
  d.getElementById('tab-robustness').click();set('rob-weight','1');
  d.getElementById('tab-evidence').click();
  assert.equal(d.getElementById('evidence-panel').hidden,false);
  assert.equal(d.getElementById('robustness-panel').hidden,true);
  assert.equal(d.getElementById('evidence').textContent,evidence);
  d.getElementById('tab-compare').click();assert.equal(d.getElementById('strategies').textContent,before);
}));
test('未知选项清空展示并报错，不残留上次可信指标',()=>run((d,set)=>{
  set('rob-scope','不存在');
  assert.equal(d.getElementById('rob-content').hidden,true);
  assert.equal(d.getElementById('rob-error').hidden,false);
  assert.match(d.getElementById('rob-error').textContent,/场景不存在/);
  set('rob-scope','ALL');assert.equal(d.getElementById('rob-content').hidden,false);
}));
test('上一轮下一轮按钮边界正确且仅回放三张卡片',()=>run((d,set)=>{
  set('rob-scope','合成近距离场景');
  assert.equal(d.getElementById('rob-previous').disabled,true);
  for(let i=0;i<5;i++)d.getElementById('rob-next').click();
  assert.equal(d.getElementById('rob-next').disabled,true);
  assert.equal(d.querySelectorAll('#rob-replay .place').length,9);
  assert.match(d.getElementById('rob-live').textContent,/第6轮/);
}));
