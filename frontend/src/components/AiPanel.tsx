import { useState } from 'react'

import { api } from '../api'
import type { AiReportResponse } from '../types'

type State =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'done'; report: AiReportResponse }
  | { kind: 'error'; message: string }

/**
 * AI 个性化解读面板。
 *
 * 用**可辨识联合类型**（discriminated union）表示四种状态，
 * 而不是写 `loading: boolean` + `error: string | null` + `data: X | null`
 * 三个独立的 state。
 *
 * 后者的经典 bug 是「组合出不可能的状态」——
 * 比如 loading 和 error 同时为 true，或者四个组合里有一个忘了处理。
 * 联合类型让**每个状态各自携带它需要的数据**，编译器会强制你在
 * switch/条件分支里处理全部情况，漏掉一个 `kind` 就编译不过。
 */
export function AiPanel({ sessionId }: { sessionId: number }) {
  const [state, setState] = useState<State>({ kind: 'idle' })

  async function load(regenerate: boolean) {
    setState({ kind: 'loading' })
    try {
      const report = await api.generateAiReport(sessionId, regenerate)
      setState({ kind: 'done', report })
    } catch (e) {
      setState({ kind: 'error', message: e instanceof Error ? e.message : String(e) })
    }
  }

  return (
    <div className="ai-block">
      <div className="ai-head">
        <span className="ai-title">AI 个性化解读</span>
        {state.kind === 'done' && (
          <span className="ai-meta">
            {state.report.provider} · {new Date(state.report.generatedAt).toLocaleString('zh-CN')}
          </span>
        )}
      </div>

      {state.kind === 'idle' && (
        <>
          <p className="ai-lead">
            上面的解读是按档位查表得到的固定文案。AI 解读会结合你这 5 个维度的组合，写一段专门针对你的分析。
          </p>
          <div className="ai-actions">
            <button className="btn btn-primary" type="button" onClick={() => load(false)}>
              生成 AI 解读
            </button>
          </div>
        </>
      )}

      {state.kind === 'loading' && (
        <div className="ai-loading">
          <div className="spinner-sm" />
          <span>正在生成…通常需要 5 到 20 秒</span>
        </div>
      )}

      {state.kind === 'error' && (
        <>
          <div className="ai-error">
            <strong>生成失败：</strong>
            {state.message}
          </div>
          <div className="ai-actions">
            <button className="btn btn-ghost" type="button" onClick={() => load(false)}>
              重试
            </button>
          </div>
        </>
      )}

      {state.kind === 'done' && (
        <Done report={state.report} onRegenerate={() => load(true)} />
      )}
    </div>
  )
}

function Done({ report, onRegenerate }: { report: AiReportResponse; onRegenerate: () => void }) {
  // 按空行分段。
  //
  // ⚠️ 这里用 `{para}` 交给 React 渲染，而不是 dangerouslySetInnerHTML。
  // React 会把文本节点转义后插入，所以模型输出里的 <script> 之类
  // 只会被当成普通文字显示。**永远不要把外部内容当 HTML 插入**——
  // 哪怕内容来自你自己调的 API，它仍然是「根据不可控输入生成的文本」。
  const paragraphs = report.content
    .split(/\n\s*\n/)
    .map((p) => p.trim())
    .filter(Boolean)

  return (
    <>
      <div className="ai-text">
        {paragraphs.map((para, i) => (
          // 这里用下标做 key 是可以的：这个列表是纯展示、不会重排、
          // 也不会增删。真正危险的是「可排序/可过滤的列表用下标做 key」。
          <p key={i}>{para}</p>
        ))}
      </div>

      <div className="ai-actions">
        <button className="btn btn-ghost" type="button" onClick={onRegenerate}>
          重新生成
        </button>
        {report.cached && (
          <span className="ai-meta">复用了上次的结果，没有重复调用 AI</span>
        )}
      </div>
    </>
  )
}
