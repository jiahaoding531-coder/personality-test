import { useState, type ReactNode } from 'react'

import type { UserResponse } from '../types'
import { ThemeToggle } from './ThemeToggle'

export type AppNavigationIcon = 'chat' | 'personality' | 'history'

export interface AppNavigationItem {
  id: string
  label: string
  icon: AppNavigationIcon
  onSelect: () => void
}

interface Props {
  activeItem: string | null
  navigation: AppNavigationItem[]
  user: UserResponse | null | undefined
  onLogin: () => void
  onLogout: () => void
  children: ReactNode
}

/**
 * TravelMind 的全局页面骨架。
 *
 * 先只定义稳定的输入边界，具体视觉与交互由测试逐步补齐。
 */
export function AppShell({
  activeItem,
  navigation,
  user,
  onLogin,
  onLogout,
  children,
}: Props) {
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false)

  function select(item: AppNavigationItem) {
    setMobileNavigationOpen(false)
    item.onSelect()
  }

  return (
    <div className="app-shell">
      <aside className="app-sidebar" aria-label="TravelMind 主导航">
        <Brand />
        <Navigation
          activeItem={activeItem}
          items={navigation}
          onSelect={select}
        />
        <AccountArea user={user} onLogin={onLogin} onLogout={onLogout} />
      </aside>

      <header className="app-mobile-header">
        <button
          className="app-menu-button"
          type="button"
          aria-label={mobileNavigationOpen ? '关闭导航' : '打开导航'}
          aria-expanded={mobileNavigationOpen}
          aria-controls="mobile-navigation"
          onClick={() => setMobileNavigationOpen((open) => !open)}
        >
          <span aria-hidden="true" />
          <span aria-hidden="true" />
          <span aria-hidden="true" />
        </button>
        <Brand compact />
        <ThemeToggle />
      </header>

      {mobileNavigationOpen && (
        <button
          className="app-nav-backdrop"
          type="button"
          aria-label="关闭导航"
          onClick={() => setMobileNavigationOpen(false)}
        />
      )}
      <aside
        id="mobile-navigation"
        className={`app-mobile-navigation${mobileNavigationOpen ? ' is-open' : ''}`}
        aria-label="TravelMind 移动导航"
        aria-hidden={!mobileNavigationOpen}
      >
        <Navigation
          activeItem={activeItem}
          items={navigation}
          onSelect={select}
        />
        <AccountArea user={user} onLogin={() => {
          setMobileNavigationOpen(false)
          onLogin()
        }} onLogout={() => {
          setMobileNavigationOpen(false)
          onLogout()
        }} />
      </aside>

      <main className="app-main">
        <div className="app-main-inner">{children}</div>
      </main>
    </div>
  )
}

function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`app-brand${compact ? ' is-compact' : ''}`} aria-label="TravelMind">
      <svg className="app-brand-mark" viewBox="0 0 48 48" aria-hidden="true">
        <path d="M5 36 18.5 12a3 3 0 0 1 5.2 0l5.1 9.2 3.3-5.4a3 3 0 0 1 5.2.1L45 30.2A4 4 0 0 1 41.5 36Z" />
        <path d="m12 36 10-17 7 12 4-7 7 12Z" />
      </svg>
      <span>
        <b>TravelMind</b>
        {!compact && <small>让每一次旅行，更适合此刻的你</small>}
      </span>
    </div>
  )
}

function Navigation({
  activeItem,
  items,
  onSelect,
}: {
  activeItem: string | null
  items: AppNavigationItem[]
  onSelect: (item: AppNavigationItem) => void
}) {
  return (
    <nav className="app-navigation">
      {items.map((item) => (
        <button
          className={`app-nav-item${activeItem === item.id ? ' is-active' : ''}`}
          type="button"
          key={item.id}
          aria-current={activeItem === item.id ? 'page' : undefined}
          onClick={() => onSelect(item)}
        >
          <NavigationIcon name={item.icon} />
          <span>{item.label}</span>
        </button>
      ))}
    </nav>
  )
}

function NavigationIcon({ name }: { name: AppNavigationIcon }) {
  const paths: Record<AppNavigationIcon, ReactNode> = {
    chat: <path d="M5 6h14v10H9l-4 3V6Zm4 4h6M9 13h4" />,
    personality: <path d="M12 4a4 4 0 0 0-4 4v1a5 5 0 0 0-3 4.6V19h14v-5.4A5 5 0 0 0 16 9V8a4 4 0 0 0-4-4Zm-2 8h4m-5 3h6" />,
    history: <path d="M7 5h10v14H7zM9 3v4m6-4v4m-5 4h4m-4 4h4" />,
  }
  return (
    <svg className="app-nav-icon" viewBox="0 0 24 24" aria-hidden="true">
      {paths[name]}
    </svg>
  )
}

function AccountArea({
  user,
  onLogin,
  onLogout,
}: {
  user: UserResponse | null | undefined
  onLogin: () => void
  onLogout: () => void
}) {
  return (
    <div className="app-account">
      <ThemeToggle />
      {user === undefined ? (
        <div className="app-account-loading" aria-label="正在确认登录状态" />
      ) : user ? (
        <>
          <div className="app-user">
            <span className="app-user-avatar" aria-hidden="true">
              {user.username.slice(0, 1).toUpperCase()}
            </span>
            <span>
              <b>{user.username}</b>
              <small>TravelMind 旅行者</small>
            </span>
          </div>
          <button className="app-account-action" type="button" onClick={onLogout}>
            退出登录
          </button>
        </>
      ) : (
        <button className="app-account-action is-primary" type="button" onClick={onLogin}>
          登录 / 注册
        </button>
      )}
    </div>
  )
}
