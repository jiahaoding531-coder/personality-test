import { useEffect, useState, type CSSProperties } from 'react'

import { api } from '../api'
import { ErrorBox, Loading } from '../components/Status'
import type { DimensionBrief, SessionSummary } from '../types'

type State =
  | { kind: 'loading' }
  | { kind: 'done'; sessions: SessionSummary[] }
  | { kind: 'error'; message: string }

export function HistoryScreen({
  onOpen,
  onBack,
}: {
  onOpen: (sessionId: number) => void
  onBack: () => void
}) {
  const [state, setState] = useState<State>({ kind: 'loading' })

  useEffect(() => {
    let cancelled = false

    api
      .getMyHistory()
      .then((sessions) => {
        // 竞态防护：如果组件在请求返回前就被卸载了（用户点了返回），
        // 这次 setState 会被丢弃，不会对已卸载的组件操作。
        // 这里用一个局部变量而不是 AbortController——请求本身很轻，
        // 没必要中断，只要别更新状态就行。
        if (!cancelled) setState({ kind: 'done', sessions })
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          setState({ kind: 'error', message: e instanceof Error ? e.message : String(e) })
        }
      })

    // 清理函数在依赖变化或卸载时执行
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <div className="card">
      <div className="result-head">
        <h2>我的测试记录</h2>
        <div className="sub">点任意一条可以查看当时的完整结果</div>
      </div>

      {state.kind === 'loading' && <Loading text="正在载入…" />}

      {state.kind === 'error' && (
        <ErrorBox
          title="载入失败"
          message={state.message}
          onRetry={() => setState({ kind: 'loading' })}
        />
      )}

      {state.kind === 'done' &&
        (state.sessions.length === 0 ? (
          <div className="empty">
            还没有测试记录。
            <br />
            回去做一次测试吧。
          </div>
        ) : (
          state.sessions.map((session) => (
            <SessionCard key={session.sessionId} session={session} onOpen={onOpen} />
          ))
        ))}

      <div className="nav-row">
        <button className="btn btn-ghost" type="button" onClick={onBack}>
          返回
        </button>
      </div>
    </div>
  )
}

function SessionCard({
  session,
  onOpen,
}: {
  session: SessionSummary
  onOpen: (sessionId: number) => void
}) {
  const done = session.dimensions.length > 0
  const when = new Date(session.submittedAt ?? session.createdAt).toLocaleString('zh-CN')

  return (
    <button className="history-item" type="button" onClick={() => onOpen(session.sessionId)}>
      <div className="top">
        <span className="when">{when}</span>
        {/* 未提交的会话没有画像——用户答到一半就关了页面，这是正常情况 */}
        <span className="badge">
          {done ? `#${session.sessionId}` : `#${session.sessionId} · 未完成`}
        </span>
      </div>

      {done && (
        <div className="mini-bars">
          {session.dimensions.map((d) => (
            <MiniBar key={d.key} dimension={d} />
          ))}
        </div>
      )}
    </button>
  )
}

/**
 * 缩略条形图。
 *
 * 和结果页的大图**共用同一套视觉语言**——同样的颜色、同样的圆角、
 * 同样的 0 基线。这样用户在两处看到的"长短"含义是一致的，
 * 不需要重新学一遍怎么读。
 *
 * 缩略图不标数值（右侧只显示分数），因为这里的目标是快速对比，
 * 不是精确读数——想看细节点进去就行。
 */
function MiniBar({ dimension }: { dimension: DimensionBrief }) {
  return (
    <div className="mini-row">
      <span className="n">{dimension.name}</span>
      <span className="t">
        <span
          className="b"
          style={{ '--w': `${dimension.score}%` } as CSSProperties}
        />
      </span>
      <span className="v">{dimension.score.toFixed(1)}</span>
    </div>
  )
}
