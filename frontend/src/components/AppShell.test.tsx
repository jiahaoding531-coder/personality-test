// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { AppShell, type AppNavigationItem } from './AppShell'

afterEach(cleanup)

beforeEach(() => {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: vi.fn().mockReturnValue({ matches: false }),
  })
})

const navigation: AppNavigationItem[] = [
  { id: 'conversation', label: '对话', icon: 'chat', onSelect: vi.fn() },
  { id: 'personality', label: '人格探索', icon: 'personality', onSelect: vi.fn() },
]

describe('AppShell', () => {
  it('只渲染调用方提供的真实功能入口', () => {
    render(
      <AppShell
        activeItem="conversation"
        navigation={navigation}
        user={{ id: 7, username: '真实用户', createdAt: '2026-09-19T00:00:00Z' }}
        onLogin={vi.fn()}
        onLogout={vi.fn()}
      >
        <p>页面内容</p>
      </AppShell>,
    )

    expect(screen.getAllByRole('button', { name: '对话' }).length).toBeGreaterThan(0)
    expect(screen.getAllByRole('button', { name: '人格探索' }).length).toBeGreaterThan(0)
    expect(screen.queryByText('我的行程')).toBeNull()
    expect(screen.queryByText('收藏')).toBeNull()
    expect(screen.getAllByText('真实用户').length).toBeGreaterThan(0)
  })

  it('移动导航在选择功能后自动收起', () => {
    const onSelect = vi.fn()
    render(
      <AppShell
        activeItem="conversation"
        navigation={[{ id: 'conversation', label: '对话', icon: 'chat', onSelect }]}
        user={null}
        onLogin={vi.fn()}
        onLogout={vi.fn()}
      >
        <p>页面内容</p>
      </AppShell>,
    )

    const menuButton = screen.getByRole('button', { name: '打开导航' })
    expect(menuButton.getAttribute('aria-expanded')).toBe('false')
    fireEvent.click(menuButton)
    expect(menuButton.getAttribute('aria-expanded')).toBe('true')

    fireEvent.click(screen.getAllByRole('button', { name: '对话' }).at(-1)!)
    expect(onSelect).toHaveBeenCalledTimes(1)
    expect(menuButton.getAttribute('aria-expanded')).toBe('false')
  })

  it('未登录时提供登录入口，不显示虚构用户信息', () => {
    const onLogin = vi.fn()
    render(
      <AppShell
        activeItem="conversation"
        navigation={navigation}
        user={null}
        onLogin={onLogin}
        onLogout={vi.fn()}
      >
        <p>页面内容</p>
      </AppShell>,
    )

    fireEvent.click(screen.getAllByRole('button', { name: '登录 / 注册' })[0]!)
    expect(onLogin).toHaveBeenCalledTimes(1)
    expect(screen.queryByText('shuaisao')).toBeNull()
  })
})
