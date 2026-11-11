import {LabInputError} from './lab-model.mjs';
export function createLabHandler({html,run}){
 let busy=false;
 return async(req,res)=>{
  const headers={'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'};
  const send=(code,value)=>{res.writeHead(code,headers);res.end(JSON.stringify(value));};
  if(req.headers.host!=='127.0.0.1:5186')return send(403,{error:'只允许本机固定地址访问。'});
  if(req.method==='GET'&&['/','/index.html'].includes(req.url)){res.writeHead(200,{...headers,'Content-Type':'text/html; charset=utf-8'});res.end(html);return;}
  if(req.url!=='/api/lab/run')return send(404,{error:'接口不存在。'});
  if(req.method!=='POST')return send(405,{error:'此接口只接受POST。'});
  if(req.headers.origin!=='http://127.0.0.1:5186')return send(403,{error:'只接受本页发起的同源请求。'});
  if(!/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(req.headers['content-type']??''))return send(415,{error:'请使用JSON参数。'});
  if(busy)return send(409,{error:'已有实验正在运行，请等待完成。'});
  busy=true;
  try{
   req.setTimeout?.(5000,()=>req.destroy());let size=0,parts=[];
   for await(const chunk of req){size+=Buffer.byteLength(chunk);if(size<=2048)parts.push(Buffer.from(chunk));}
   if(size>2048)return send(413,{error:'请求过大。'});
   let options;try{options=JSON.parse(Buffer.concat(parts).toString('utf8'));}catch{return send(400,{error:'参数不是有效JSON。'});}
   const result=await run(options);send(200,result);
  }catch(error){
   if(error instanceof LabInputError)send(400,{error:error.message});
   else send(503,{error:'本地实验未完成。请检查独立Java构建及本机运行记录，再手动重试。'});
  }finally{busy=false;}
 };
}
