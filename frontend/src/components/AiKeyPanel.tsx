import { useState } from 'react'

import {
  AI_PROVIDERS,
  clearAiSettings,
  loadAiSettings,
  maskKey,
  saveAiSettings,
  type AiProviderId,
} from '../aiSettings'

/**
 * 「用我自己的 AI Key」设置面板。
 *
 * <h2>它解决的问题</h2>
 *
 * <p>部署出去之后，如果只有服务端那一把 key，那所有访客的 AI 调用都算在
 * 部署者头上——想发个 demo 给别人看就得替所有人付费。这个面板让访客
 * 填自己的 key，部署者一分钱不花。
 *
 * <h2>⚠️ 对用户要说实话：key 去哪了</h2>
 *
 * <p>下面那段说明不是客套话，是这个功能能不能被信任的前提。
 * 让用户把 API Key 粘进一个陌生网站，本来就该被警惕——
 * 我们必须把"它存在哪、什么时候发出去、会不会被保存"讲清楚，
 * 而且是**当面讲**，不是藏在某个隐私政策里。
 *
 * @param onSaved 保存后回调。调用方通常用它来立刻重试上一次失败的请求——
 *                填完 key 还要用户再点一次"生成"，是很没耐心的设计
 */
export function AiKeyPanel({
  onSaved,
  defaultOpen = false,
  hint,
}: {
  onSaved?: () => void
  defaultOpen?: boolean
  /** 面板上方的一句话，用来解释"为什么现在需要你填" */
  hint?: string
}) {
  const [open, setOpen] = useState(defaultOpen)
  const [provider, setProvider] = useState<AiProviderId>(() => loadAiSettings().provider)
  const [key, setKey] = useState(() => loadAiSettings().key)
  const [saved, setSaved] = useState(false)

  const configured = loadAiSettings().key.trim().length > 0

  function handleSave() {
    saveAiSettings({ provider, key })
    setSaved(true)
    onSaved?.()
  }

  function handleClear() {
    clearAiSettings()
    setKey('')
    setSaved(false)
    // 不调 onSaved：清掉 key 之后通常没有可重试的东西了
  }

  if (!open) {
    return (
      <div className="ai-key-collapsed">
        <button className="link-btn" type="button" onClick={() => setOpen(true)}>
          {configured ? `AI 设置（已填 ${maskKey(loadAiSettings().key)}）` : '用我自己的 AI Key'}
        </button>
      </div>
    )
  }

  return (
    <div className="ai-key-panel">
      {hint && <p className="ai-key-hint">{hint}</p>}

      <div className="ai-key-row">
        <label className="ai-key-label" htmlFor="ai-provider">
          厂商
        </label>
        <select
          id="ai-provider"
          className="ai-key-select"
          value={provider}
          onChange={(e) => setProvider(e.target.value as AiProviderId)}
        >
          {AI_PROVIDERS.map((p) => (
            <option key={p.id} value={p.id}>
              {p.label}
            </option>
          ))}
        </select>
      </div>

      <div className="ai-key-row">
        <label className="ai-key-label" htmlFor="ai-key">
          API Key
        </label>
        <input
          id="ai-key"
          className="ai-key-input"
          type="password"
          autoComplete="off"
          spellCheck={false}
          placeholder="粘贴所选厂商的 API Key"
          value={key}
          onChange={(e) => {
            setKey(e.target.value)
            setSaved(false)
          }}
        />
      </div>

      {/*
        ⚠️ 这段要说实话，而且要说全。"你的 key 很安全"这种话没有信息量，
        用户没法据它做判断。逐条讲清"存在哪、什么时候发出去、会不会被保存"，
        他才能自己判断要不要填——这才是尊重。
      */}
      <p className="ai-key-note">
        Key <b>只存在你自己的浏览器里</b>（localStorage）。生成解读时它随请求发给我们，
        由我们转交给所选厂商，<b>用完即弃、不写入数据库、不留在服务器内存里</b>。
        换台电脑要重新填。不放心的话，去厂商后台新建一个专用 key，随时可以吊销。
      </p>

      <div className="ai-key-actions">
        <button className="btn btn-primary" type="button" onClick={handleSave} disabled={!key.trim()}>
          保存
        </button>
        {configured && (
          <button className="btn btn-ghost" type="button" onClick={handleClear}>
            清除
          </button>
        )}
        <span className="spacer" />
        <button className="link-btn" type="button" onClick={() => setOpen(false)}>
          收起
        </button>
      </div>

      {saved && <p className="ai-key-saved">已保存，只存在这台设备的浏览器里。</p>}
    </div>
  )
}
