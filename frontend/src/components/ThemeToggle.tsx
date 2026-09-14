import { useEffect, useState } from 'react'

type Theme = 'light' | 'dark'

/** 读出当前生效的主题：优先用户手动选择，其次跟随系统。 */
function readTheme(): Theme {
  const attr = document.documentElement.getAttribute('data-theme')
  if (attr === 'light' || attr === 'dark') return attr
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

/**
 * 主题切换。
 *
 * 它做的事就是把 `data-theme` 写到 `<html>` 上，剩下全交给 CSS——
 * 样式表里 `:root[data-theme='dark']` 那一块会覆盖掉全部颜色变量。
 *
 * **为什么不用 CSS-in-JS 或给每个组件传 theme？**
 * 因为颜色变量本身就在 CSS 层做级联，JS 只需要翻转一个属性。
 * 加一层 JS 状态同步反而更容易出现"某个组件没跟着变"的问题。
 */
export function ThemeToggle() {
  // 把 readTheme 作为惰性初始化函数传进去（注意不是 readTheme()）。
  // 传函数时 React 只在首次渲染调用它一次；传调用结果则每次渲染都会执行。
  const [theme, setTheme] = useState<Theme>(readTheme)

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme)
  }, [theme])

  return (
    <button
      className="theme-btn"
      type="button"
      onClick={() => setTheme((t) => (t === 'dark' ? 'light' : 'dark'))}
    >
      切换主题
    </button>
  )
}
