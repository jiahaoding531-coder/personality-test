import type { BehaviorDraft, BehaviorEnvelope, BehaviorMetadata, BehaviorReceipt, BehaviorScope } from './behaviorTypes'

export const createBehaviorMetadata = (): BehaviorMetadata => ({ eventId: crypto.randomUUID(), occurredAt: new Date().toISOString() })
export const isTemporaryBehaviorError = (error: unknown) => {
  const status = typeof error === 'object' && error !== null && 'status' in error ? error.status : 0
  return status === 0 || (typeof status === 'number' && status >= 500 && status <= 599)
}

/** 内存单飞队列：100 条含在途，最多 3 次重试，10 秒请求超时，动作 120 秒过期。 */
export function createBehaviorClient(
  send: (scope: BehaviorScope, event: BehaviorEnvelope, signal?: AbortSignal) => Promise<BehaviorReceipt>,
  scope: BehaviorScope,
) {
  type Item = { event: BehaviorEnvelope; attempts: number; expiresAt: number }
  const queue: Item[] = []
  let disposed = false, running = false
  let timer: ReturnType<typeof setTimeout> | undefined
  let timeout: ReturnType<typeof setTimeout> | undefined
  let controller: AbortController | undefined
  const pump = async () => {
    if (disposed || running || timer !== undefined) return
    while (queue[0] && Date.now() >= queue[0].expiresAt) queue.shift()
    const item = queue[0]; if (!item) return
    running = true; controller = new AbortController()
    let retry = false
    try {
      await Promise.race([
        send(scope, item.event, controller.signal),
        new Promise<never>((_resolve, reject) => { timeout = setTimeout(() => { controller?.abort(); reject(new TypeError('行为发送超时')) }, Math.min(10000,item.expiresAt-Date.now())) }),
      ])
    } catch (error) { retry = isTemporaryBehaviorError(error) && item.attempts < 3 && Date.now() < item.expiresAt }
    finally { clearTimeout(timeout); running = false }
    if (disposed) return
    if (retry) { timer = setTimeout(() => { timer=undefined; void pump() }, Math.min(1000 * 2 ** item.attempts++, item.expiresAt-Date.now())) }
    else { queue.shift(); void pump() }
  }
  return {
    record(draft: BehaviorDraft): void {
      if (disposed || queue.length >= 100) return
      // 来源快照不可被调用方之后的状态修改污染。这里没有 GPS、Key 或任意 metadata。
      queue.push({ event: { ...structuredClone(draft), ...createBehaviorMetadata(), schemaVersion: 1 }, attempts: 0, expiresAt: Date.now()+120000 })
      void pump()
    },
    dispose(): void { disposed = true; clearTimeout(timer); clearTimeout(timeout); controller?.abort(); queue.length = 0 },
  }
}
