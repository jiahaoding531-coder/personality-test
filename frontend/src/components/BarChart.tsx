import { useState, type CSSProperties } from 'react'

import type { DimensionResult } from '../types'

/**
 * 5 个人格维度的横向条形图。
 *
 * **为什么是横向条形图而不是雷达图？**
 * 雷达图是人格测试的常见做法，但它有两个硬伤：面积会随维度排列顺序
 * 变化而产生视觉误导，而五个维度的顺序本身是任意的；而且人眼比较
 * 角度/面积远不如比较长度准确。数据可视化里「比较量级」对应的标准形式
 * 就是条形图。
 *
 * **为什么五根柱子用同一个颜色而不是深浅渐变？**
 * 五个维度之间没有天然顺序，用颜色深浅编码等于把柱长重复编码一遍，
 * 白白浪费了颜色这个通道。一个系列 = 一种颜色。
 *
 * 图表本身**不需要图例**——只有一种颜色，标题已经说明画的是什么。
 */

interface TipState {
  x: number
  y: number
  dimension: DimensionResult
}

export function BarChart({ dimensions }: { dimensions: DimensionResult[] }) {
  const [tip, setTip] = useState<TipState | null>(null)

  return (
    <>
      <div className="plot">
        {dimensions.map((d) => (
          <div
            key={d.key}
            className="bar-row"
            onMouseMove={(e) => setTip({ x: e.clientX, y: e.clientY, dimension: d })}
            onMouseLeave={() => setTip(null)}
          >
            <div className="dim-name">{d.name}</div>

            <div className="track">
              {/*
                用 CSS 自定义属性传宽度，而不是直接设 style.width。
                CSS 里 width: var(--w) 配合 @keyframes 做入场动画——
                这样即使动画因为任何原因没跑（无头浏览器、reduced-motion），
                元素最终也会落到 --w 这个静态值上，正确性不依赖时序。

                TypeScript 不知道 '--w' 是合法的 CSS 属性名，
                所以要断言成 CSSProperties。这是自定义属性唯一的类型代价。
              */}
              <div className="bar" style={{ '--w': `${d.score}%` } as CSSProperties} />
            </div>

            {/*
              数值直接标注在柱子右侧，而不是只放在悬停提示里。
              提示是「增强」，不能是读到数值的**唯一**途径——
              触屏设备没有 hover，键盘用户也未必能触发。
            */}
            <div className="val">{d.score.toFixed(2)}</div>
            <div className="lvl">{d.levelLabel}</div>
          </div>
        ))}
      </div>

      <div className="axis-row">
        <div className="axis-ticks">
          <span>0</span>
          <span>25</span>
          <span>50</span>
          <span>75</span>
          <span>100</span>
        </div>
      </div>

      {tip && <Tip {...tip} />}
    </>
  )
}

/** 悬停提示。用 fixed 定位，跟随鼠标但不会超出视口右边缘。 */
function Tip({ x, y, dimension }: TipState) {
  const TIP_WIDTH = 200
  const left = Math.min(x + 14, window.innerWidth - TIP_WIDTH - 12)
  const top = Math.max(8, y - 58)

  return (
    <div className="tip" style={{ left, top }}>
      <div>
        <b>{dimension.name}</b>
      </div>
      <div>
        分数 {dimension.score.toFixed(2)} · {dimension.levelLabel}
      </div>
    </div>
  )
}
