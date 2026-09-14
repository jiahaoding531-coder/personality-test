import { useState } from 'react'

import { AiPanel } from '../components/AiPanel'
import { BarChart } from '../components/BarChart'
import type { SessionResultResponse } from '../types'

export function ResultScreen({
  result,
  onRestart,
}: {
  result: SessionResultResponse
  onRestart: () => void
}) {
  const [showTable, setShowTable] = useState(false)

  return (
    <div className="card">
      <div className="result-head">
        <h2>你的人格画像</h2>
        <div className="sub">
          {result.dimensions.length} 个维度 · 完成于{' '}
          {new Date(result.submittedAt ?? result.createdAt).toLocaleString('zh-CN')}
        </div>
      </div>

      {/* 单一系列 → 不需要图例，标题已经说明画的是什么 */}
      <figure style={{ margin: 0 }}>
        <BarChart dimensions={result.dimensions} />
      </figure>

      {/*
        表格视图是图表的无障碍等价形式。
        色觉障碍、屏幕阅读器用户，或者单纯想看精确数值的人都能用。
        图表里的数值虽然已经直接标注了，但表格还额外提供了档位这一列。
      */}
      <button className="table-toggle" type="button" onClick={() => setShowTable((v) => !v)}>
        {showTable ? '隐藏表格视图' : '显示表格视图'}
      </button>
      {showTable && (
        <table className="data">
          <thead>
            <tr>
              <th>维度</th>
              <th style={{ textAlign: 'right' }}>分数</th>
              <th>档位</th>
            </tr>
          </thead>
          <tbody>
            {result.dimensions.map((d) => (
              <tr key={d.key}>
                <td>{d.name}</td>
                <td className="num">{d.score.toFixed(2)}</td>
                <td>{d.levelLabel}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <div className="readings">
        {result.dimensions.map((d) => (
          <div className="reading" key={d.key}>
            <div className="head">
              <span className="nm">{d.name}</span>
              <span className="sc">
                {d.score.toFixed(2)} · {d.levelLabel}
              </span>
            </div>
            <p>{d.description}</p>
          </div>
        ))}
      </div>

      <AiPanel sessionId={result.sessionId} />

      <div className="disclaimer">{result.disclaimer}</div>

      <div className="nav-row">
        <button className="btn btn-ghost" type="button" onClick={onRestart}>
          重新测试
        </button>
      </div>
    </div>
  )
}
