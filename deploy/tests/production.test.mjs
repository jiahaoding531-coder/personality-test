import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

// 使用真实 Compose 解析器，断言最终生效的配置，不靠搜索 YAML 文本制造通过。
const deploy = fileURLToPath(new URL('../', import.meta.url))
const fixtureEnv = {
  ...process.env,
  DEPLOY_DOMAIN: 'travelmind.localhost',
  ACME_EMAIL: 'ops@example.invalid',
  RELEASE_TAG: 'deployment-contract-test',
  POSTGRES_DB: 'travelmind_production',
  POSTGRES_ADMIN_PASSWORD: 'test-only-admin-not-a-deployment-secret',
  DB_USER: 'travelmind_app',
  DB_PASSWORD: 'test-only-app-not-a-deployment-secret',
  BACKUP_DIR: './backups',
  AMAP_ENABLED: 'false',
  AMAP_KEY: '',
  AI_ENABLED: 'false',
  AI_ALLOW_USER_KEYS: 'false',
  DEEPSEEK_API_KEY: '',
}

function render(extra = {}) {
  const result = spawnSync('docker', ['compose', '--env-file', '.env.example', '-f',
    'compose.production.yml', 'config', '--format', 'json'], {
    cwd: deploy, env: { ...fixtureEnv, ...extra }, encoding: 'utf8', timeout: 20000,
  })
  return result
}

function config() {
  const result = render()
  assert.equal(result.status, 0, '生产 Compose 应能成功解析（当前尚未实现）')
  return JSON.parse(result.stdout)
}

test('只有 Caddy 发布 80 和 443，数据库、后端和前端均不发布端口', () => {
  const { services } = config()
  assert.deepEqual(Object.keys(services).sort(), ['backend', 'caddy', 'frontend', 'postgres'])
  for (const name of ['backend', 'frontend', 'postgres']) assert.equal(services[name].ports, undefined)
  assert.deepEqual(services.caddy.ports.map(p => Number(p.published)).sort((a,b) => a-b), [80,443])
})

test('生产运行只加载指定镜像，禁止在 4 GB 服务器执行构建', () => {
  for (const service of Object.values(config().services)) {
    assert.ok(service.image)
    assert.equal(service.build, undefined)
    assert.ok(service.mem_limit > 0)
    assert.equal(service.logging.options['max-file'], '3')
  }
  const total = Object.values(config().services).reduce((n,s) => n+Number(s.mem_limit), 0)
  assert.ok(total <= 3 * 1024 ** 3, '至少给系统留下约 1 GB 内存')
})

test('数据库网络隔离且只对后端开放内部连接', () => {
  const { services, networks } = config()
  assert.equal(networks.data.internal, true)
  assert.deepEqual(Object.keys(services.postgres.networks), ['data'])
  assert.ok(Object.hasOwn(services.backend.networks, 'data'))
  assert.equal(services.frontend.networks.data, undefined)
  assert.equal(services.caddy.networks.data, undefined)
})

test('缺少数据库口令时立即拒绝配置，不回落到开发默认密码', () => {
  for (const key of ['DB_PASSWORD', 'POSTGRES_ADMIN_PASSWORD']) {
    const result = render({ [key]: '' })
    assert.notEqual(result.status, 0)
    assert.match(result.stderr, new RegExp(key))
  }
})

test('前端和 Caddy 不接收高德、模型和数据库凭据', () => {
  const { services } = config()
  for (const name of ['frontend', 'caddy']) {
    for (const key of Object.keys(services[name].environment ?? {}))
      assert.doesNotMatch(key, /PASSWORD|AMAP|DEEPSEEK|TOKEN|SECRET|API_KEY/)
  }
  assert.equal(services.backend.environment.SPRING_PROFILES_ACTIVE, 'prod')
  assert.equal(services.backend.environment.DB_USER, 'travelmind_app')
  assert.notEqual(services.postgres.environment.POSTGRES_PASSWORD, services.backend.environment.DB_PASSWORD)
})

test('数据库和证书持久化，备份与维护脚本具有明确挂载', () => {
  const { services, volumes } = config()
  for (const volume of ['pgdata', 'caddy_data', 'caddy_config']) assert.ok(volumes[volume])
  assert.ok(services.postgres.volumes.some(v => v.target === '/var/lib/postgresql/data' && v.type === 'volume'))
  assert.ok(services.postgres.volumes.some(v => v.target === '/backups' && v.type === 'bind'))
  assert.ok(services.postgres.volumes.some(v => v.target === '/maintenance' && v.read_only))
  assert.ok(services.caddy.volumes.some(v => v.target === '/data' && v.type === 'volume'))
  assert.ok(services.backend.healthcheck)
  assert.ok(services.frontend.healthcheck)
})

test('生产AI保持关闭且未指定模型时采用Flash，显式模型配置仍可覆盖', () => {
  const defaults = render({ AI_MODEL: '' })
  assert.equal(defaults.status, 0)
  const environment = JSON.parse(defaults.stdout).services.backend.environment
  assert.equal(environment.AI_ENABLED, 'false')
  assert.equal(environment.AI_ALLOW_USER_KEYS, 'false')
  assert.equal(environment.AI_MODEL, 'deepseek-flash')
  const override = render({ AI_MODEL: 'controlled-model-fixture' })
  assert.equal(override.status, 0)
  assert.equal(JSON.parse(override.stdout).services.backend.environment.AI_MODEL, 'controlled-model-fixture')
})
