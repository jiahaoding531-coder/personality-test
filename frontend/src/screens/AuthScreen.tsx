import { useState, type FormEvent } from 'react'

import { api } from '../api'
import type { UserResponse } from '../types'

type Mode = 'login' | 'register'

/**
 * 登录 / 注册。两种模式共用一个表单——字段完全一样（用户名 + 密码），
 * 分成两个组件只会带来重复代码。
 */
export function AuthScreen({
  onSuccess,
  onBack,
}: {
  onSuccess: (user: UserResponse) => void
  onBack: () => void
}) {
  const [mode, setMode] = useState<Mode>('login')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const isRegister = mode === 'register'

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (isRegister) {
        // 注册接口只建账号、不建会话（后端刻意如此，职责更清晰）。
        // 所以这里注册成功后立刻再登录一次，让用户不用手动跳一步。
        await api.register(username, password)
      }
      onSuccess(await api.login(username, password))
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  function switchMode() {
    setMode(isRegister ? 'login' : 'register')
    setError(null)
  }

  return (
    <div className="card">
      <h2>{isRegister ? '注册账号' : '登录'}</h2>
      <p className="lede" style={{ marginTop: 8 }}>
        {isRegister
          ? '注册后你做的测试会保存下来，可以随时回看历次结果。'
          : '登录后可以查看自己的测试历史。不登录也能做测试，只是不留记录。'}
      </p>

      {error && <div className="form-error">{error}</div>}

      <form onSubmit={handleSubmit}>
        <div className="field">
          <label htmlFor="username">用户名</label>
          <input
            id="username"
            name="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoFocus
            required
          />
          {isRegister && (
            <div className="hint-text">3~50 位，只能用字母、数字、下划线和短横线</div>
          )}
        </div>

        <div className="field">
          <label htmlFor="password">密码</label>
          <input
            id="password"
            name="password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            // 让浏览器/密码管理器知道这是"注册新密码"还是"现有密码"，
            // 它才能正确地生成和填充。这两个值写错会导致密码管理器行为异常。
            autoComplete={isRegister ? 'new-password' : 'current-password'}
            required
          />
          {isRegister && <div className="hint-text">至少 8 位</div>}
        </div>

        <div className="nav-row" style={{ marginTop: 20 }}>
          <button className="btn btn-primary" type="submit" disabled={busy}>
            {busy ? '请稍候…' : isRegister ? '注册并登录' : '登录'}
          </button>
          <button className="btn btn-ghost" type="button" onClick={onBack} disabled={busy}>
            返回
          </button>
        </div>
      </form>

      <div className="mode-switch">
        {isRegister ? '已经有账号了？' : '还没有账号？'}
        <button className="link-btn" type="button" onClick={switchMode} style={{ marginLeft: 4 }}>
          {isRegister ? '去登录' : '注册一个'}
        </button>
      </div>
    </div>
  )
}
