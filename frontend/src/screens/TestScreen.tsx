import { useCallback, useEffect, useRef, useState } from 'react'

import type { Question, ScaleOption } from '../types'

interface Props {
  questions: Question[]
  options: ScaleOption[]
  /** questionId → 分值 */
  answers: Record<number, number>
  onAnswer: (questionId: number, score: number) => void
  onFinish: () => void
}

/**
 * 答题页。
 *
 * 这里有一个**必须用 useRef 而不是 useState 保存的定时器句柄**——
 * 原因见 chose() 里的注释。这是从静态版移植过来时最容易改错的地方。
 */
export function TestScreen({ questions, options, answers, onAnswer, onFinish }: Props) {
  const [index, setIndex] = useState(0)

  /**
   * 自动跳转的定时器句柄。
   *
   * **为什么用 useRef 而不是 useState？**
   * 因为改它不该触发重新渲染（它只是给 setTimeout/clearTimeout 用的，
   * 不参与界面展示）。用 useState 会造成额外的渲染，还可能引发
   * 「渲染 → 改状态 → 再渲染」的循环。
   *
   * ref 是 React 里存放「与渲染无关的可变值」的地方——
   * DOM 节点、定时器句柄、上一次的值，都属于这一类。
   */
  const advanceRef = useRef<number | null>(null)

  const clearAdvance = useCallback(() => {
    if (advanceRef.current !== null) {
      window.clearTimeout(advanceRef.current)
      advanceRef.current = null
    }
  }, [])

  // 组件卸载时清理待执行的定时器。
  // 不清理的话，定时器触发时组件已经没了，会对着已卸载的组件调 setState
  // （React 18 起不再报警告，但那次更新会被丢弃，行为仍然不符合预期）。
  useEffect(() => clearAdvance, [clearAdvance])

  const goTo = useCallback(
    (target: number) => {
      clearAdvance() // 任何主动导航都先取消待执行的自动跳转
      setIndex(Math.max(0, Math.min(target, questions.length - 1)))
    },
    [clearAdvance, questions.length],
  )

  const choose = useCallback(
    (questionId: number, score: number) => {
      // ⚠️ 这一行是关键。
      //
      // 旧版静态实现里，每次点击都新排一个 setTimeout 却从不取消。
      // 连点两次 → 两个定时器先后触发 → index 一次跳两格 →
      // 中间那道题被整道跳过，用户还完全看不出来。
      // 跳过题的后果是它永远没有答案，「查看结果」按钮永远不出现。
      //
      // 取消掉上一个定时器，连点就只会前进一格。
      clearAdvance()

      onAnswer(questionId, score)

      if (index < questions.length - 1) {
        advanceRef.current = window.setTimeout(() => {
          advanceRef.current = null
          // 用函数式更新 setIndex(i => ...) 而不是 setIndex(index + 1)。
          // 前者拿到的 i 永远是「最新的」状态，不受闭包捕获时机影响。
          setIndex((i) => Math.min(i + 1, questions.length - 1))
        }, 260)
      }
    },
    [clearAdvance, index, onAnswer, questions.length],
  )

  // ---------- 键盘快捷键 ----------
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      const tag = (e.target as HTMLElement | null)?.tagName ?? ''
      if (tag === 'INPUT' || tag === 'TEXTAREA' || e.metaKey || e.ctrlKey || e.altKey) return

      const current = questions[index]
      if (!current) return

      if (e.key >= '1' && e.key <= '5') {
        const opt = options.find((o) => String(o.value) === e.key)
        if (opt) {
          choose(current.id, opt.value)
          e.preventDefault()
        }
      } else if (e.key === 'ArrowLeft') {
        goTo(index - 1)
        e.preventDefault()
      } else if (e.key === 'ArrowRight') {
        goTo(index + 1)
        e.preventDefault()
      }
    }

    document.addEventListener('keydown', onKeyDown)
    // 返回清理函数：effect 重新执行或组件卸载时移除监听。
    // 少了这行，每次 index 变化都会叠加一个监听器，
    // 按一次键会触发 N 次选择。
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [choose, goTo, index, options, questions])

  // ---------- 派生状态 ----------
  // 这些都能从 props 和 state 算出来，所以**不要**再用 useState 存一份。
  // 「派生状态存两份」是 React 里最常见的 bug 来源——
  // 两份数据一旦不同步，界面就会显示错误但很难查。
  const total = questions.length
  const answeredCount = questions.filter((q) => answers[q.id] !== undefined).length
  const missing = total - answeredCount
  const firstUnanswered = questions.findIndex((q) => answers[q.id] === undefined)
  const current = questions[index]

  if (!current) return null // 防御：正常情况下不会发生

  function handleResultClick() {
    clearAdvance()
    // 没答完就跳到第一道漏答的题，而不是什么都不做——
    // 让按钮「有反应且指向下一步」，比一个沉默的按钮有用得多。
    if (firstUnanswered >= 0) {
      goTo(firstUnanswered)
      return
    }
    onFinish()
  }

  return (
    <div className="card">
      <div className="progress-head">
        <span>
          第 {index + 1} 题 / 共 {total} 题
        </span>
        {/* 显示「已答 N / 20」而不是百分比——完成度这件事，个数比比例直观得多 */}
        <span>
          已答 {answeredCount} / {total}
        </span>
      </div>
      <div className="progress-track">
        <div className="progress-fill" style={{ width: `${(answeredCount / total) * 100}%` }} />
      </div>

      <p className="q-text">{current.content}</p>

      <div className="options">
        {options.map((opt) => {
          const chosen = answers[current.id] === opt.value
          return (
            <button
              key={opt.value}
              type="button"
              className={chosen ? 'opt chosen' : 'opt'}
              onClick={() => choose(current.id, opt.value)}
            >
              <span className="mark" />
              <span>{opt.label}</span>
            </button>
          )
        })}
      </div>

      {missing > 0 && (
        <div className="hint">
          <strong>还有 {missing} 题没答。</strong> 点下面答题卡里的数字可以直接跳过去。
        </div>
      )}

      <div className="dots-head">答题卡</div>
      <AnswerSheet
        questions={questions}
        answers={answers}
        currentIndex={index}
        onJump={goTo}
      />

      <div className="nav-row">
        <button
          className="btn btn-ghost"
          type="button"
          disabled={index === 0}
          onClick={() => goTo(index - 1)}
        >
          上一题
        </button>
        <button
          className="btn btn-ghost"
          type="button"
          disabled={index === total - 1}
          onClick={() => goTo(index + 1)}
        >
          下一题
        </button>
        <span className="spacer" />
        <button className="btn btn-primary" type="button" onClick={handleResultClick}>
          {missing === 0 ? '查看结果' : `还差 ${missing} 题`}
        </button>
      </div>

      <div className="kbd-hint">
        也可以用键盘：<kbd>1</kbd>–<kbd>5</kbd> 选择，<kbd>←</kbd>
        <kbd>→</kbd> 翻页
      </div>
    </div>
  )
}

/** 答题卡：一眼看出哪些题没答，点数字直接跳过去。 */
function AnswerSheet({
  questions,
  answers,
  currentIndex,
  onJump,
}: {
  questions: Question[]
  answers: Record<number, number>
  currentIndex: number
  onJump: (index: number) => void
}) {
  return (
    <div className="dots">
      {questions.map((q, i) => {
        const answered = answers[q.id] !== undefined
        const classes = ['dot-item']
        if (answered) classes.push('answered')
        if (i === currentIndex) classes.push('current')

        return (
          <button
            key={q.id}
            type="button"
            className={classes.join(' ')}
            title={`第 ${i + 1} 题${answered ? '（已答）' : '（未答）'}`}
            onClick={() => onJump(i)}
          >
            {i + 1}
          </button>
        )
      })}
    </div>
  )
}
