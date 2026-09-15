/**
 * 访客自己的大模型凭据（BYOK）。
 *
 * <h2>⚠️ key 只存在这个浏览器里</h2>
 *
 * <p>它进 `localStorage`，每次 AI 请求随请求头发给后端，**后端用完即弃**——
 * 不落库、不进服务端内存。所以：
 * <ul>
 *   <li>清掉浏览器数据 = 清掉 key，不会在任何地方残留</li>
 *   <li>换台电脑要重新填</li>
 *   <li>后端那侧看不到"你曾经填过什么"</li>
 * </ul>
 *
 * <p>这正是选这个方案而不是"存服务端会话"的原因：让别人的凭证长期留在
 * 我们的服务器上，是负债，不是功能。
 */

/** 和后端的 `AiProvider` 枚举一一对应。⚠️ 改了那边要同步改这里。 */
export type AiProviderId = 'DEEPSEEK' | 'DASHSCOPE' | 'ZHIPU' | 'MOONSHOT'

export const AI_PROVIDERS: { id: AiProviderId; label: string }[] = [
  { id: 'DEEPSEEK', label: 'DeepSeek' },
  { id: 'DASHSCOPE', label: '通义千问' },
  { id: 'ZHIPU', label: '智谱 GLM' },
  { id: 'MOONSHOT', label: 'Kimi' },
]

const PROVIDER_KEY = 'travelmind.aiProvider'
const API_KEY = 'travelmind.aiKey'

export interface AiSettings {
  provider: AiProviderId
  key: string
}

export function loadAiSettings(): AiSettings {
  try {
    const provider = localStorage.getItem(PROVIDER_KEY)
    return {
      provider: isKnownProvider(provider) ? provider : 'DEEPSEEK',
      key: localStorage.getItem(API_KEY) ?? '',
    }
  } catch {
    // ⚠️ 隐私模式 / 禁用 Cookie 时 localStorage 会直接抛异常。
    // 读不到偏好就当作"没填过"，不该让整个页面崩掉。
    return { provider: 'DEEPSEEK', key: '' }
  }
}

export function saveAiSettings(settings: AiSettings): void {
  try {
    localStorage.setItem(PROVIDER_KEY, settings.provider)
    localStorage.setItem(API_KEY, settings.key.trim())
  } catch {
    // 存不下就只影响"下次还要重填"，不影响这次使用
  }
}

export function clearAiSettings(): void {
  try {
    localStorage.removeItem(API_KEY)
  } catch {
    // 同上
  }
}

/** 有没有填过 key。界面上据此决定是"去填"还是"清除"。 */
export function hasAiKey(): boolean {
  return loadAiSettings().key.trim().length > 0
}

/**
 * 给 AI 请求用的两个头。
 *
 * <p>没填 key 就返回空对象——**不要发一个空的 `X-AI-Key`**，
 * 后端会把它当成"访客想用自己的 key 但填了个空的"，
 * 虽然最终也会退回服务端配置，但让语义干净些更好。
 */
export function aiRequestHeaders(): Record<string, string> {
  const { provider, key } = loadAiSettings()
  const trimmed = key.trim()
  if (!trimmed) {
    return {}
  }
  return {
    'X-AI-Provider': provider,
    'X-AI-Key': trimmed,
  }
}

/**
 * 把 key 打码，只留头尾。
 *
 * <p>界面上永远不完整显示它——**截图、录屏、身后站着人**都是很现实的泄露场景，
 * 而这些场景里用户根本不会意识到自己正在暴露什么。
 */
export function maskKey(key: string): string {
  const trimmed = key.trim()
  if (trimmed.length <= 10) {
    return '•'.repeat(trimmed.length)
  }
  return `${trimmed.slice(0, 6)}${'•'.repeat(10)}${trimmed.slice(-4)}`
}

function isKnownProvider(value: string | null): value is AiProviderId {
  return value !== null && AI_PROVIDERS.some((p) => p.id === value)
}
