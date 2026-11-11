import test, { before } from 'node:test'
import assert from 'node:assert/strict'
import https from 'node:https'
import http from 'node:http'
import { spawnSync } from 'node:child_process'
import { randomBytes, randomUUID } from 'node:crypto'

const project='travelmind-deploycheck-20261012'
const origin='https://travelmind.localhost:18443'
let ca
const jar=new Map()
function docker(args) { return spawnSync('docker',args,{encoding:'utf8',timeout:15000}) }
before(()=>{
  const result=docker(['exec',`${project}-caddy-1`,'cat','/data/caddy/pki/authorities/local/root.crt'])
  assert.equal(result.status,0,'本地 HTTPS 验收栈应已启动；不连接任何公网域名')
  ca=result.stdout
})
function request(path,{method='GET',body,headers={},cookies=true,timeout=10000}={}) {
  return new Promise((resolve,reject)=>{
    const payload=body===undefined ? undefined : JSON.stringify(body)
    const req=https.request({hostname:'127.0.0.1',port:18443,servername:'travelmind.localhost',ca,
      path,method,headers:{Host:'travelmind.localhost:18443',
        ...(cookies && jar.size ? {Cookie:[...jar].map(([k,v])=>`${k}=${v}`).join('; ')}:{}),
        ...(payload ? {'Content-Type':'application/json','Content-Length':Buffer.byteLength(payload)}:{}),...headers}},res=>{
      let text=''; res.setEncoding('utf8'); res.on('data',part=>text+=part)
      res.on('end',()=>{
        if(cookies) for(const cookie of res.headers['set-cookie']??[]) {
          const pair=cookie.split(';')[0], i=pair.indexOf('='); jar.set(pair.slice(0,i),pair.slice(i+1))
        }
        resolve({status:res.statusCode,headers:res.headers,text,json:()=>JSON.parse(text)})
      })
    })
    req.setTimeout(timeout,()=>req.destroy(new Error('本地 HTTPS 验收请求超时')))
    req.on('error',reject); if(payload) req.write(payload); req.end()
  })
}
function csrf() { return {'X-XSRF-TOKEN':decodeURIComponent(jar.get('XSRF-TOKEN')??''),Origin:origin} }

test('HTTPS 验证本地 CA 后返回生产页面和静态资源，不运行 Vite',async()=>{
  const page=await request('/')
  assert.equal(page.status,200)
  assert.match(page.text,/TravelMind/)
  assert.doesNotMatch(page.text,/@vite\/client/)
  const asset=page.text.match(/src="(\/assets\/[^\"]+\.js)"/)[1]
  const js=await request(asset)
  assert.equal(js.status,200); assert.match(js.headers['cache-control'],/immutable/)
})

test('HTTP 自动跳转 HTTPS，API 健康检查真正连接数据库',async()=>{
  const redirect=await new Promise((resolve,reject)=>{
    http.get({hostname:'127.0.0.1',port:18080,path:'/',headers:{Host:'travelmind.localhost'}},res=>{
      res.resume(); resolve({status:res.statusCode,location:res.headers.location})
    }).on('error',reject)
  })
  assert.equal(redirect.status,308); assert.equal(redirect.location,'https://travelmind.localhost/')
  const health=await request('/api/health')
  assert.equal(health.status,200); assert.equal(health.json().database,'UP')
})

test('同源 HTTPS 注册登录与 Cookie 正确，无 CSRF 和非法 Origin 被拒绝',async()=>{
  // 前面的健康检查也会下发令牌；这里模拟新浏览器首次访问，而不是要求服务器重复设置 Cookie。
  jar.clear()
  const me=await request('/api/auth/me'); assert.equal(me.status,200)
  const xsrf=(me.headers['set-cookie']??[]).find(c=>c.startsWith('XSRF-TOKEN='))
  assert.match(xsrf,/Secure/i); assert.doesNotMatch(xsrf,/HttpOnly/i)
  const credentials={username:`deployqa_${randomBytes(5).toString('hex')}`,password:randomBytes(18).toString('hex')}
  assert.equal((await request('/api/auth/register',{method:'POST',body:credentials,headers:{Origin:origin}})).status,403)
  assert.equal((await request('/api/auth/register',{method:'POST',body:credentials,headers:{...csrf(),Origin:'https://untrusted.example'}})).status,403)
  const registered=await request('/api/auth/register',{method:'POST',body:credentials,headers:csrf()})
  assert.equal(registered.status,201)
  const logged=await request('/api/auth/login',{method:'POST',body:credentials,headers:csrf()})
  assert.equal(logged.status,200)
  const session=(logged.headers['set-cookie']??[]).find(c=>c.startsWith('JSESSIONID='))
  assert.match(session,/Secure/i); assert.match(session,/HttpOnly/i); assert.match(session,/SameSite=Lax/i)
  const current=await request('/api/auth/me'); assert.equal(current.json().username,credentials.username)
})

test('生产镜像旅行测试和景点推荐可用，反馈与事件保持同一事务',async()=>{
  const questions=(await request('/api/questions?scale=TRAVEL')).json().questions
  assert.equal(questions.length,8)
  const session=await request('/api/travel/sessions',{method:'POST',body:{},headers:csrf()})
  assert.equal(session.status,201)
  const id=session.json().sessionId ?? session.json().id
  assert.ok(id)
  const saved=await request(`/api/travel/sessions/${id}/answers`,{method:'POST',body:{answers:questions.map(q=>({questionId:q.id,score:3}))},headers:csrf()})
  assert.equal(saved.status,200)
  assert.equal((await request(`/api/travel/sessions/${id}/submit`,{method:'POST',headers:csrf()})).status,200)
  const rec=await request(`/api/travel/sessions/${id}/recommendations`,{method:'POST',body:{latitude:30.2420,longitude:120.1400,remainingMinutes:240,maxDistanceKm:10,autoInfer:false},headers:csrf()})
  assert.equal(rec.status,200)
  const places=rec.json().places
  assert.ok(places.length>0 && places.length<=3)
  const target=places[0].recommendationId
  const eventId=randomUUID()
  const feedbackHeaders={...csrf(),'X-Behavior-Event-Id':eventId,'X-Behavior-Occurred-At':new Date().toISOString(),'X-Feedback-Version':'0'}
  const path=`/api/travel/sessions/${id}/recommendations/${target}/feedback`
  const first=await request(path,{method:'POST',body:{reaction:'LIKE'},headers:feedbackHeaders})
  assert.equal(first.status,200); assert.equal(first.json().feedbackState.version,1)
  const repeat=await request(path,{method:'POST',body:{reaction:'LIKE'},headers:feedbackHeaders})
  assert.equal(repeat.status,200); assert.equal(repeat.json().feedbackState.version,1)
  assert.equal((await request(`/api/travel/sessions/${id}/profile`,{cookies:false})).status,404)
})

test('伪造 Forwarded 协议不会覆盖 Caddy 的 HTTPS 信息',async()=>{
  const response=await request('/api/auth/me',{cookies:false,headers:{Forwarded:'for=203.0.113.1;proto=http;host=evil.example','X-Forwarded-Proto':'http','X-Forwarded-Host':'evil.example'}})
  assert.equal(response.status,200)
  assert.ok((response.headers['set-cookie']??[]).some(c=>c.startsWith('XSRF-TOKEN=') && /Secure/i.test(c)))
})

test('代理故障不把自带模型密钥或会话令牌写入运行日志',{timeout:90000},async()=>{
  const marker=`privacy-probe-${randomBytes(12).toString('hex')}`
  assert.equal(docker(['stop','--time','5',`${project}-backend-1`]).status,0)
  try {
    const failed=await request(`/api/auth/me?probe=${marker}`,{cookies:false,headers:{'X-AI-Key':marker,'X-Session-Token':marker}})
    assert.equal(failed.status,502)
    const logs=docker(['logs',`${project}-caddy-1`])
    assert.ok(!(logs.stdout+logs.stderr).includes(marker),'故障日志不得包含请求密钥、令牌或查询内容')
  } finally {
    assert.equal(docker(['start',`${project}-backend-1`]).status,0)
    let recovered=false
    for(let i=0;i<30;i++) {
      try { if((await request('/api/health')).status===200) { recovered=true; break } } catch {}
      await new Promise(resolve=>setTimeout(resolve,1000))
    }
    assert.ok(recovered,'只重启本地验收后端，等待恢复')
  }
})

test('数据库故障时公网健康检查只返回最小状态，不透传内部异常',{timeout:90000},async()=>{
  assert.equal(docker(['stop','--time','5',`${project}-postgres-1`]).status,0)
  try {
    const response=await request('/api/health',{timeout:45000})
    assert.equal(response.status,503)
    assert.deepEqual(response.json(),{status:'DOWN'})
  } finally {
    assert.equal(docker(['start',`${project}-postgres-1`]).status,0)
    let recovered=false
    for(let i=0;i<30;i++) {
      try { if((await request('/api/health',{timeout:4000})).status===200) { recovered=true; break } } catch {}
      await new Promise(resolve=>setTimeout(resolve,1000))
    }
    assert.ok(recovered,'仅本地验收数据库应恢复，原开发库不受影响')
  }
})

test('数据库异常 DETAIL 不进入 JDBC 或数据库运行日志',async()=>{
  const marker=`private-detail-${randomBytes(12).toString('hex')}`
  const sql=`CREATE OR REPLACE FUNCTION deployment_checks.reject_probe_user() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.username LIKE 'privacy_trigger_%' THEN RAISE EXCEPTION 'privacy regression probe' USING DETAIL='${marker}'; END IF; RETURN NEW; END $$; CREATE TRIGGER deployment_privacy_probe BEFORE INSERT ON users FOR EACH ROW EXECUTE FUNCTION deployment_checks.reject_probe_user();`
  assert.equal(docker(['exec',`${project}-postgres-1`,'psql','-U','travelmind_app','-d','travelmind_production','-v','ON_ERROR_STOP=1','-c',sql]).status,0)
  try {
    await request('/api/auth/me')
    const response=await request('/api/auth/register',{method:'POST',headers:csrf(),body:{username:`privacy_trigger_${randomBytes(4).toString('hex')}`,password:randomBytes(18).toString('hex')}})
    assert.equal(response.status,500)
    for(const service of ['backend','postgres']) {
      const logs=docker(['logs',`${project}-${service}-1`])
      assert.ok(!(logs.stdout+logs.stderr).includes(marker),`${service} 运行日志不得包含数据库错误 DETAIL 私密内容`)
    }
  } finally {
    // 仅清除本测试在隔离库建立的触发器/函数，绝不触碰开发数据或清空数据库。
    assert.equal(docker(['exec',`${project}-postgres-1`,'psql','-U','travelmind_app','-d','travelmind_production','-v','ON_ERROR_STOP=1','-c','DROP TRIGGER IF EXISTS deployment_privacy_probe ON users; DROP FUNCTION IF EXISTS deployment_checks.reject_probe_user();']).status,0)
  }
})
