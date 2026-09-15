import { useState } from 'react'

import { ApiError, api } from '../api'
import { AiKeyPanel } from './AiKeyPanel'
import type { AiReportResponse } from '../types'

type State =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'done'; report: AiReportResponse }
  | { kind: 'error'; message: string }
  /**
   * 这台服务器没替访客配 AI（后端 501）。
   *
   * <p>⚠️ 单独一个状态，而不是并进 {@code error}：它的处理方式完全不同——
   * 不是"出错了，重试一下"，而是"**你可以填自己的 key 就能用**"。
   * 混进 error 里显示一个红色的"生成失败"，就把一条可走的路说成了死路。
   */
  | { kind: 'noServerAi' }
  /** 填的 key 被厂商拒了（后端 400）。该改输入，不是重试 */
  | { kind: 'keyRejected' }

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
export function AiPanel({
  sessionId,
  sessionToken,
}: {
  sessionId: number
  /** 匿名测试时需要带上，否则服务端返回 404（无权访问该会话） */
  sessionToken?: string
}) {
  const [state, setState] = useState<State>({ kind: 'idle' })

  async function load(regenerate: boolean) {
    setState({ kind: 'loading' })
    try {
      const report = await api.generateAiReport(sessionId, regenerate, sessionToken)
      setState({ kind: 'done', report })
    } catch (e) {
      if (e instanceof ApiError && e.status === 501) {
        setState({ kind: 'noServerAi' })
        return
      }
      if (e instanceof ApiError && e.status === 400) {
        // 后端刻意把"调用方给的东西有问题"（400）和"上游故障"（502）分开，
        // 就是为了让这里能分辨该"重填 key"还是该"重试"
        setState({ kind: 'keyRejected' })
        return
      }
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
          {/* 平时也能主动去填自己的 key —— 不是只有出错了才让你知道有这条路 */}
          <AiKeyPanel onSaved={() => load(false)} />
        </>
      )}

      {state.kind === 'noServerAi' && (
        <>
          <p className="ai-lead">这台服务器没有配 AI。</p>
          <AiKeyPanel
            defaultOpen
            onSaved={() => load(false)}
            hint="填上你自己的 API Key 就能用了。"
          />
        </>
      )}

      {state.kind === 'keyRejected' && (
        <>
          <p className="ai-lead">你填的 AI Key 被厂商拒绝了（可能填错、过期或额度用尽）。</p>
          <AiKeyPanel defaultOpen onSaved={() => load(false)} hint="换一个 key 再试试。" />
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
