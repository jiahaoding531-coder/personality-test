import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

import App from './App'
import './styles.css'

const rootEl = document.getElementById('root')
if (!rootEl) {
  // 与其让 createRoot 抛一个 "Argument of type null" 的隐晦错误，
  // 不如在这里给出明确的提示。这类"启动即失败"的问题，
  // 报错信息越具体，排查时间越短。
  throw new Error('找不到 #root 节点，请检查 index.html')
}

createRoot(rootEl).render(
  // StrictMode 在开发模式下会**故意把每个组件渲染两次**，
  // 用来暴露副作用写得不干净的地方（比如在渲染函数里改状态、
  // 或者 useEffect 没有正确清理）。
  //
  // 它只影响开发模式，生产构建里会被自动移除。
  // 第一次看到"为什么我的 console.log 打印了两次"时别慌，这是正常的。
  <StrictMode>
    <App />
  </StrictMode>,
)
