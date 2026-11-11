import {fileURLToPath} from 'node:url';
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';
import vm from 'node:vm';
const html=fs.readFileSync(new URL('./index.html',import.meta.url),'utf8');
const manifest=JSON.parse(fs.readFileSync(new URL('./manifest.json',import.meta.url)));
const data=JSON.parse(html.match(/<script id="demo-data" type="application\/json">([\s\S]*?)<\/script>/)[1]);
const base=fileURLToPath(new URL('./fixtures/robustness/',import.meta.url));
const bytes=fs.readFileSync(base+'report.json');

test('单文件网页完整内嵌原始50次报告，离线不需要取数',()=>{
  assert.ok(data.robustness,'尚未内嵌多种子报告');
  const hash=value=>crypto.createHash('sha256').update(JSON.stringify(value)).digest('hex');
  assert.equal(hash(data.robustness),hash(JSON.parse(bytes)));
  assert.doesNotMatch(html,/<script[^>]+src=|<link[^>]+href=["']https?:/);
});
test('新报告及网页构建校验和可追溯',()=>{
  assert.equal(manifest.robustnessReportSha256,crypto.createHash('sha256').update(bytes).digest('hex'));
  assert.equal(manifest.htmlSha256,crypto.createHash('sha256').update(html).digest('hex'));
});
test('页面包含独立统计与单次回放控件及统计限制说明',()=>{
  for(const id of ['tab-robustness','robustness-panel','rob-scope','rob-weight','rob-seed','rob-query','rob-summaries','rob-trend','rob-replay'])
    assert.ok(html.includes(`id="${id}"`),`缺少${id}`);
  assert.match(html,/非置信区间/); assert.match(html,/不是独立用户样本/);
});
test('内嵌脚本能够解析，无构建替换占位符残留',()=>{
  const script=html.match(/<script>\s*([\s\S]*?)<\/script>/)[1];
  assert.doesNotThrow(()=>new vm.Script(script));
  assert.doesNotMatch(html,/__DATA__|__MODEL__|__ROBUSTNESS_UI__/);
});

test('下载副本与原文件字节校验和明确区分',()=>{
  assert.match(html,/<button id="rob-download">下载多种子 JSON 数据副本<\/button>/);
  assert.match(html,/下载副本会重新排版，字节校验和与原文件不同/);
  assert.doesNotMatch(html,/<button[^>]*>下载原始实验报告<\/button>/);
});
test('全局说明区分报告回放和本机实时运行，始终标明合成数据',()=>{
  assert.match(html,/<strong>内置数据：合成样例<\/strong>/);
  assert.match(html,/实验台可主动调用本机Java重算/);
});
test('网页构建保留实际源文件摘要，避免自检把旧构建当新版本',()=>{
 for(const name of ['page.html','demo-model.mjs','robustness-ui.js','fieldwork-model.mjs','fieldwork-ui.js','lab-ui.js','result-view-model.mjs','result-view-ui.js','ranking-evidence.mjs','ranking-evidence-ui.js']){
  assert.equal(manifest.webSourceSha256?.[name],crypto.createHash('sha256').update(fs.readFileSync(new URL(name,import.meta.url))).digest('hex'));
 }
});
