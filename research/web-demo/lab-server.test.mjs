import test from 'node:test';
import assert from 'node:assert/strict';
import {Readable} from 'node:stream';
const api=await import('./lab-server.mjs').catch(e=>{if(e.code==='ERR_MODULE_NOT_FOUND')return {};throw e;});
function handler(run){assert.equal(typeof api.createLabHandler,'function','缺少受限本机实验接口');return api.createLabHandler({html:'<p>TEST_ONLY</p>',run});}
async function request(handle,{method='POST',url='/api/lab/run',body='{}',headers={}}={}){
 const req=Readable.from([body]);Object.assign(req,{method,url,headers:{host:'127.0.0.1:5186',origin:'http://127.0.0.1:5186','content-type':'application/json',...headers}});
 let code,output;const done=new Promise(resolve=>{const res={writeHead(c,h){code=c;this.headers=h;},end(x){output=x;resolve(this);}};Promise.resolve(handle(req,res)).catch(e=>resolve({error:e}));});
 const result=await done;if(result.error)throw result.error;return {code,body:output,headers:result.headers};
}
test('合法同源POST调用固定运行器，响应不允许缓存',async()=>{let calls=0;const r=await request(handler(async()=>{calls++;return {testOnly:true};}));assert.equal(r.code,200);assert.equal(calls,1);assert.equal(r.headers['Cache-Control'],'no-store');});
test('拒绝跨站Origin和伪造Host，不调用Java',async()=>{let calls=0;const h=handler(async()=>calls++);for(const headers of [{origin:'https://evil.test'},{host:'evil.test:5186'},{origin:'null'},{origin:undefined}])assert.equal((await request(h,{headers})).code,403);assert.equal(calls,0);});
test('拒绝错误方法、内容类型、超大JSON和不合法JSON',async()=>{const h=handler(async()=>({}));for(const [input,code] of [[{method:'PUT'},405],[{headers:{'content-type':'text/plain'}},415],[{body:' '.repeat(2049)},413],[{body:'{'},400]])assert.equal((await request(h,input)).code,code);});
test('全局并发一，第二个请求明确拒绝而非排队',async()=>{let release;const h=handler(()=>new Promise(r=>release=r));const first=request(h);await new Promise(r=>setImmediate(r));const second=await request(h);assert.equal(second.code,409);release({});assert.equal((await first).code,200);});
test('运行失败不回显私有路径与命令，下一次仍可重试',async()=>{let calls=0;const h=handler(async()=>{if(calls++===0)throw Error('C:/private-key/secret');return {};});const r=await request(h);assert.equal(r.code,503);assert.doesNotMatch(r.body,/private-key|secret/);assert.equal((await request(h)).code,200);});
test('原首页仍可GET且只提供固定路径，不开放任意文件下载',async()=>{const h=handler(async()=>({}));assert.equal((await request(h,{method:'GET',url:'/'})).code,200);assert.equal((await request(h,{method:'GET',url:'/../.env'})).code,404);});
