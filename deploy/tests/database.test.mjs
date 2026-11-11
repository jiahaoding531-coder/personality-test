import test, { before } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { fileURLToPath } from 'node:url'

const deploy = fileURLToPath(new URL('../', import.meta.url))
const project = 'travelmind-deploycheck-20261012'
let env
function docker(args, options = {}) {
  return spawnSync('docker', args, { cwd: deploy, env, encoding: 'utf8', timeout: 90000, ...options })
}
function compose(args) {
  return docker(['compose', '--env-file', '.env.example', '-f', 'compose.production.yml',
    '-f', 'tests/compose.smoke.yml', '-p', project, ...args])
}
function exec(...args) { return compose(['exec', '-T', 'postgres', ...args]) }
function ok(result, message) {
  // 不把 Docker 完整错误/环境输出写进报告，避免无意打印凭据。
  assert.equal(result.status, 0, message)
  return result.stdout.trim()
}

before(async () => {
  env = { ...process.env, DEPLOY_DOMAIN: 'travelmind.localhost', ACME_EMAIL: 'ops@example.invalid',
    RELEASE_TAG: 'localcheck-20261012', POSTGRES_DB: 'travelmind_production', DB_USER: 'travelmind_app',
    POSTGRES_ADMIN_PASSWORD: randomBytes(24).toString('hex'), DB_PASSWORD: randomBytes(24).toString('hex'),
    BACKUP_DIR: fileURLToPath(new URL('../backups/', import.meta.url)), AMAP_ENABLED: 'false', AMAP_KEY: '',
    AI_ENABLED: 'false', AI_ALLOW_USER_KEYS: 'false', DEEPSEEK_API_KEY: '' }
  // 同一隔离验收项目可再次验证，不重置卷。只从这个容器读取其测试口令到进程内存。
  const existing = docker(['inspect', `${project}-postgres-1`])
  if (existing.status === 0) {
    const values = Object.fromEntries(JSON.parse(existing.stdout)[0].Config.Env.map(v => {
      const i=v.indexOf('='); return [v.slice(0,i),v.slice(i+1)]
    }))
    env.POSTGRES_ADMIN_PASSWORD=values.POSTGRES_PASSWORD
    env.DB_PASSWORD=values.DB_APP_PASSWORD
  }
  ok(compose(['up', '-d', 'postgres']), '隔离 PostgreSQL 应能启动')
  for (let i=0;i<30;i++) {
    if (exec('pg_isready','-U','postgres','-d','travelmind_production').status===0) return
    await new Promise(resolve=>setTimeout(resolve,1000))
  }
  assert.fail('隔离数据库未就绪，未连接任何开发数据库')
})

test('备份真实数据库后只恢复到新库，恢复内容一致', () => {
  ok(exec('psql','-U','travelmind_app','-d','travelmind_production','-v','ON_ERROR_STOP=1','-c',
    'CREATE SCHEMA IF NOT EXISTS deployment_checks; ALTER TABLE IF EXISTS public.deployment_restore_probe SET SCHEMA deployment_checks; CREATE TABLE IF NOT EXISTS deployment_checks.deployment_restore_probe(id integer primary key, value text); INSERT INTO deployment_checks.deployment_restore_probe VALUES(1,\'restore-proof\') ON CONFLICT(id) DO UPDATE SET value=excluded.value;'), '应能建立恢复探针')
  const backup = ok(exec('bash','/maintenance/backup-db.sh'), '备份脚本应生成完整备份')
  assert.match(backup,/^travelmind-[A-Za-z0-9_-]+\.dump$/)
  assert.equal(ok(exec('stat','-c','%a',`/backups/${backup}`),'备份权限应可检查'),'600')
  const target=`travelmind_restore_${randomBytes(5).toString('hex')}`
  ok(exec('bash','/maintenance/restore-db.sh',backup,target,'--confirm-new-database'),'应恢复到新库')
  assert.equal(ok(exec('psql','-U','travelmind_app','-d',target,'-At','-c','SELECT value FROM deployment_checks.deployment_restore_probe WHERE id=1'),'应能读取恢复结果'),'restore-proof')
  const repeated=exec('bash','/maintenance/restore-db.sh',backup,target,'--confirm-new-database')
  assert.equal(repeated.status,3,'已有恢复库必须拒绝覆盖')
})

test('拒绝恢复到生产库、任意数据库和目录穿越文件', () => {
  for (const args of [ ['travelmind-test.dump','travelmind_production','--confirm-new-database'],
    ['travelmind-test.dump','postgres','--confirm-new-database'],
    ['../private.dump','travelmind_restore_invalid','--confirm-new-database'] ]) {
    assert.equal(exec('bash','/maintenance/restore-db.sh',...args).status,2)
  }
})

test('应用数据库用户不是超级用户，不能创建数据库或角色', () => {
  assert.equal(ok(exec('psql','-U','postgres','-d','postgres','-At','-c',
    "SELECT rolsuper,rolcreatedb,rolcreaterole FROM pg_roles WHERE rolname='travelmind_app'"),'应能检查应用角色'),'f|f|f')
})

test('同一秒和固定随机种子下的多次备份不得覆盖历史文件',()=>{
  // 只固定命名依赖，pg_dump、文件系统和权限仍使用真实 PostgreSQL 容器。
  const command="date() { printf '20260101T000000Z\\n'; }; RANDOM=123; source /maintenance/backup-db.sh"
  const first=ok(exec('bash','-c',command),'第一次备份应成功')
  const second=ok(exec('bash','-c',command),'第二次备份应成功')
  assert.notEqual(first,second,'即使命名随机源重复，也不能覆盖前一次备份')
})
