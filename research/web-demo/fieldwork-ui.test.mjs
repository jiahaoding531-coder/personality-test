import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createRequire} from 'node:module';
import {fixture} from './fieldwork-fixture.mjs';
const require=createRequire(new URL('../../frontend/package.json',import.meta.url));
const {JSDOM,VirtualConsole}=require('jsdom');
const html=fs.readFileSync(new URL('./index.html',import.meta.url),'utf8');
async function run(check){
  const errors=[],console=new VirtualConsole();console.on('jsdomError',e=>errors.push(e.message));
  const dom=new JSDOM(html,{runScripts:'dangerously',url:'http://127.0.0.1:5186/',virtualConsole:console});
  const d=dom.window.document;const input=d.getElementById('fieldwork-file');assert.ok(input,'尚无本地采集结果导入入口');
  const choose=(file)=>{Object.defineProperty(input,'files',{configurable:true,value:file?[file]:[]});input.dispatchEvent(new dom.window.Event('change'));};
  const file=(value)=>({size:1000,name:'fieldwork-review.json',text:async()=>typeof value==='string'?value:JSON.stringify(value)});
  const flush=()=>new Promise(r=>setImmediate(r));
  try{await check({d,dom,choose,file,flush});assert.deepEqual(errors,[]);}finally{dom.window.close();}
}
test('本地导入展示节点与记录数，明确保持未核验',()=>run(async({d,choose,file,flush})=>{
  choose(file(fixture()));await flush();assert.equal(d.getElementById('fieldwork-result').hidden,false);
  assert.match(d.getElementById('fieldwork-result').textContent,/YEX-01/);assert.match(d.getElementById('fieldwork-result').textContent,/未授予实验资格/);
  assert.doesNotMatch(d.getElementById('fieldwork-result').textContent,/原始经度|TEST_ONLY.jpg/);
}));
test('非法文件替换时清除旧结果并展示错误',()=>run(async({d,choose,file,flush})=>{
  choose(file(fixture()));await flush();choose(file('{bad'));await flush();assert.equal(d.getElementById('fieldwork-result').hidden,true);assert.equal(d.getElementById('fieldwork-error').hidden,false);
}));
test('快速选择新旧文件，旧读取完成后不能覆盖新结果',()=>run(async({d,choose,file,flush})=>{
  let release;choose({size:10,text:()=>new Promise(r=>release=r)});const fresh=fixture();fresh.facts[0].observation='新文件';choose(file(fresh));await flush();release(JSON.stringify(fixture()));await flush();assert.match(d.getElementById('fieldwork-result').textContent,/新文件/);
}));
test('清空时正在读取的文件也失效，页面不保留资料',()=>run(async({d,choose,flush})=>{
  let release;choose({size:10,text:()=>new Promise(r=>release=r)});d.getElementById('fieldwork-clear').click();release(JSON.stringify(fixture()));await flush();assert.equal(d.getElementById('fieldwork-result').hidden,true);assert.equal(d.getElementById('fieldwork-result').textContent,'');
}));
test('取消选择保留原结果，不产生空白新记录',()=>run(async({d,choose,file,flush})=>{choose(file(fixture()));await flush();const t=d.getElementById('fieldwork-result').textContent;choose(null);await flush();assert.equal(d.getElementById('fieldwork-result').textContent,t);}));
test('HTML输入只作为文字，不加载图片或执行脚本',()=>run(async({d,choose,file,flush})=>{
  const f=fixture();f.facts[0].observation='<img src=x onerror="alert(1)">';choose(file(f));await flush();const result=d.getElementById('fieldwork-result');assert.equal(result.querySelector('img'),null);assert.match(result.textContent,/<img/);
}));
test('超大文件不调用text读取，不回显文件内容',()=>run(async({d,choose,flush})=>{let calls=0;choose({size:1048577,text:async()=>{calls++;return 'secret';}});await flush();assert.equal(calls,0);assert.match(d.getElementById('fieldwork-error').textContent,/过大/);}));
test('读取错误后可重选同文件，页签切换不污染合成实验',()=>run(async({d,choose,file,flush})=>{
  const before=d.getElementById('strategies').innerHTML;choose({size:5,text:async()=>{throw Error('private');}});await flush();assert.doesNotMatch(d.getElementById('fieldwork-error').textContent,/private/);
  choose(file(fixture()));await flush();d.getElementById('tab-compare').click();assert.equal(d.getElementById('strategies').innerHTML,before);
}));
test('采集导入模块不含网络上传或浏览器持久化操作',()=>{assert.match(html,/fieldwork-file/);const ui=fs.readFileSync(new URL('./fieldwork-ui.js',import.meta.url),'utf8');assert.doesNotMatch(ui,/localStorage|sessionStorage|indexedDB|XMLHttpRequest|fetch\s*\(/);});
