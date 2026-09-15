import { useCallback, useEffect, useState } from 'react'

import { api } from './api'
import { ErrorBox, Loading } from './components/Status'
import { ThemeToggle } from './components/ThemeToggle'
import { AuthScreen } from './screens/AuthScreen'
import { HistoryScreen } from './screens/HistoryScreen'
import { HomeScreen } from './screens/HomeScreen'
import { ResultScreen } from './screens/ResultScreen'
import { TestScreen } from './screens/TestScreen'
import { TravelResultScreen } from './screens/TravelResultScreen'
import type {
  Question,
  ScaleOption,
  SessionResultResponse,
  TravelProfileResponse,
  UserResponse,
} from './types'

/**
 * 当前在哪一屏。
 *
 * 用联合类型而不是几个 boolean（`isLoading` / `hasError` / `showResult`）。
 * 后者能表示 2³ = 8 种组合，其中大部分是无意义的
 * （比如同时「加载中」又「有错误」又「要展示结果」）。
 * 联合类型只允许 7 种合法状态，编译器帮你排除掉那些不可能的情况。
 */
type Screen =
  | 'home'
  | 'loading'
  | 'test'
  | 'result'
  | 'error'
  | 'auth'
  | 'history'
  | 'travel-test'
  | 'travel-result'

/** 一次测试流程中的全部数据。 */
interface Flow {
  sessionId: number | null
  /**
   * 会话访问令牌。匿名测试时必须带着它才能答题、提交、查结果。
   *
   * `sessionId` 是自增整数（猜得到），令牌是随机 UUID（猜不到）——
   * 只有令牌能证明"这个会话是我建的"。
   */
  accessToken: string | null
  questions: Question[]
  options: ScaleOption[]
  /** questionId → 用户选的分值 */
  answers: Record<number, number>
  result: SessionResultResponse | null
}

const EMPTY_FLOW: Flow = {
  sessionId: null,
  accessToken: null,
  questions: [],
  options: [],
  answers: {},
  result: null,
}

/**
 * 一次旅行测试流程中的全部数据。
 *
 * 和 {@link Flow} 一样是"把一次流程的数据打包成一个对象"——
 * 而不是散着加五六个 useState。这样 `restart` 只需要重置一个对象，
 * 也不会出现"重置了题号却忘了清答案"这种半残状态。
 *
 * 答题页复用同一个 `TestScreen`，所以 questions/options/answers 的形状完全一致。
 */
interface TravelFlow {
  sessionId: number | null
  accessToken: string | null
  questions: Question[]
  options: ScaleOption[]
  answers: Record<number, number>
  profile: TravelProfileResponse | null
}

const EMPTY_TRAVEL_FLOW: TravelFlow = {
  sessionId: null,
  accessToken: null,
  questions: [],
  options: [],
  answers: {},
  profile: null,
}

export default function App() {
  const [screen, setScreen] = useState<Screen>('home')
  const [loadingText, setLoadingText] = useState('')
  const [flow, setFlow] = useState<Flow>(EMPTY_FLOW)
  const [travelFlow, setTravelFlow] = useState<TravelFlow>(EMPTY_TRAVEL_FLOW)
  const [error, setError] = useState<{ title: string; message: string } | null>(null)

  /**
   * 当前登录用户。null = 未登录。
   *
   * `undefined` 表示"还没查过"——这个区分很重要：
   * 如果初始值是 null，界面会在启动的一瞬间显示"未登录"，
   * 然后 /me 返回后才切换到已登录状态，用户会看到一次闪烁。
   */
  const [user, setUser] = useState<UserResponse | null | undefined>(undefined)

  /** 把任意异常转成可展示的错误屏。 */
  const fail = useCallback((title: string, e: unknown) => {
    setError({ title, message: e instanceof Error ? e.message : String(e) })
    setScreen('error')
  }, [])

  /** 打开某次历史结果（也用于分享链接 /?result=5）。 */
  const openResult = useCallback(
    async (sessionId: number) => {
      setLoadingText('正在载入画像…')
      setScreen('loading')
      try {
        const result = await api.getResult(sessionId)
        setFlow({ ...EMPTY_FLOW, sessionId: result.sessionId, result })
        setScreen('result')
      } catch (e: unknown) {
        fail('无法载入结果', e)
      }
    },
    [fail],
  )

  // ---------------------------------------------------------------
  // 启动时：查当前用户；如果有 ?result=N 就直接打开那次结果
  // ---------------------------------------------------------------
  useEffect(() => {
    // 查当前用户。
    // 未登录时后端返回 200 + 空体（不是 401），所以这里不会走 catch。
    api
      .getCurrentUser()
      .then(setUser)
      .catch(() => setUser(null)) // 后端没起来也别卡住首页

    const sid = new URLSearchParams(window.location.search).get('result')
    if (sid) {
      void openResult(Number(sid))
    }
  }, [openResult])

  /** 开始新一轮测试：建会话 + 取题目，两个请求并发发出。 */
  const startTest = useCallback(async () => {
    setLoadingText('正在准备题目…')
    setScreen('loading')
    try {
      // 这两个请求互不依赖，用 Promise.all 并发发出，
      // 总耗时约等于较慢的那个，而不是两个相加。
      //
      // 如果已登录，后端会自动把这次会话关联到当前用户——
      // 不需要前端传任何用户标识，身份从服务端会话里取。
      const [session, bank] = await Promise.all([api.createSession(), api.getQuestions()])
      setFlow({
        sessionId: session.sessionId,
        accessToken: session.accessToken,
        questions: bank.questions,
        options: bank.options,
        answers: {},
        result: null,
      })
      setScreen('test')
    } catch (e: unknown) {
      fail('无法开始测试', e)
    }
  }, [fail])

  /** 提交并计分。 */
  const finishTest = useCallback(async () => {
    const { sessionId, accessToken, questions, answers } = flow
    if (sessionId === null) return

    setLoadingText('正在计算结果…')
    setScreen('loading')
    try {
      const payload = questions.map((q) => ({ questionId: q.id, score: answers[q.id] ?? 0 }))
      // 带上令牌——匿名测试时服务端只认它
      await api.saveAnswers(sessionId, payload, accessToken ?? undefined)
      const result = await api.submit(sessionId, accessToken ?? undefined)
      setFlow((f) => ({ ...f, result }))
      setScreen('result')
    } catch (e: unknown) {
      // 失败时退回答题页，用户的答案还在，不用重答
      fail('计算失败', e)
    }
  }, [flow, fail])

  const recordAnswer = useCallback((questionId: number, score: number) => {
    setFlow((f) => ({ ...f, answers: { ...f.answers, [questionId]: score } }))
  }, [])

  const recordTravelAnswer = useCallback((questionId: number, score: number) => {
    setTravelFlow((f) => ({ ...f, answers: { ...f.answers, [questionId]: score } }))
  }, [])

  // ---------------------------------------------------------------
  // 旅行偏好测试
  // ---------------------------------------------------------------

  /**
   * 开始旅行测试。
   *
   * <p><b>先看用户上次测过没有。</b>测过就直接用那份画像进结果页——
   * 让他为了一份一个字都不会变的问卷再答 8 道题，那是问卷不是助手。
   * 没测过（或匿名）才去答题。
   *
   * <p>探测"有没有历史画像"靠的是 `GET profile` 的 200/404：
   * 后端在读取时有 fallback（本会话没有 → 找该用户最近一份），
   * 所以这个接口的语义其实就是"这个用户当前的旅行画像"。
   */
  const startTravelTest = useCallback(async () => {
    setLoadingText('正在准备旅行测试…')
    setScreen('loading')
    try {
      const session = await api.createTravelSession()
      const base = {
        sessionId: session.sessionId,
        accessToken: session.accessToken,
        questions: [] as Question[],
        options: [] as ScaleOption[],
        answers: {},
      }

      // 先试历史画像。拿到就直接开始，拿不到（404）才去答题
      try {
        const reused = await api.getTravelProfile(session.sessionId, session.accessToken)
        setTravelFlow({ ...base, profile: reused })
        setScreen('travel-result')
        return
      } catch {
        // 404 = 这个人没测过。这是正常路径，不是错误
      }

      const bank = await api.getTravelQuestions()
      setTravelFlow({
        ...base,
        questions: bank.questions,
        options: bank.options,
        profile: null,
      })
      setScreen('travel-test')
    } catch (e: unknown) {
      fail('无法开始旅行测试', e)
    }
  }, [fail])

  /** 提交旅行测试，得到 8 维画像。 */
  const finishTravelTest = useCallback(async () => {
    const { sessionId, accessToken, questions, answers } = travelFlow
    if (sessionId === null) return

    setLoadingText('正在计算旅行画像…')
    setScreen('loading')
    try {
      const payload = questions.map((q) => ({ questionId: q.id, score: answers[q.id] ?? 0 }))
      await api.saveTravelAnswers(sessionId, payload, accessToken ?? undefined)
      const profile = await api.submitTravel(sessionId, accessToken ?? undefined)
      setTravelFlow((f) => ({ ...f, profile }))
      setScreen('travel-result')
    } catch (e: unknown) {
      fail('计算失败', e)
    }
  }, [travelFlow, fail])

  const restart = useCallback(() => {
    setFlow(EMPTY_FLOW)
    setTravelFlow(EMPTY_TRAVEL_FLOW)
    setScreen('home')
  }, [])

  const handleLogout = useCallback(async () => {
    try {
      await api.logout()
    } catch {
      // 注销失败也要把本地状态清掉——否则用户会卡在"看起来已登录
      // 但服务端已经不认"的状态里。服务端会话可能已经过期了。
    }
    setUser(null)
    setFlow(EMPTY_FLOW)
    setScreen('home')
  }, [])

  return (
    <div className="wrap">
      <div className="topbar">
        <div className="brand">
          人格测试<span>5 维画像 · React 版</span>
        </div>

        <div className="nav-user">
          {user === undefined ? null : user ? (
            <>
              <button
                className="theme-btn"
                type="button"
                onClick={() => setScreen('history')}
              >
                我的记录
              </button>
              <span className="name">
                <b>{user.username}</b>
              </span>
              <button className="link-btn" type="button" onClick={handleLogout}>
                注销
              </button>
            </>
          ) : (
            <button className="theme-btn" type="button" onClick={() => setScreen('auth')}>
              登录 / 注册
            </button>
          )}
          <ThemeToggle />
        </div>
      </div>

      {screen === 'home' && (
        <HomeScreen onStart={startTest} onStartTravel={startTravelTest} />
      )}

      {screen === 'auth' && (
        <AuthScreen
          onSuccess={(u) => {
            setUser(u)
            setScreen('home')
          }}
          onBack={() => setScreen('home')}
        />
      )}

      {screen === 'history' && (
        <HistoryScreen onOpen={openResult} onBack={() => setScreen('home')} />
      )}

      {screen === 'loading' && (
        <div className="card">
          <Loading text={loadingText} />
        </div>
      )}

      {screen === 'test' && (
        <TestScreen
          questions={flow.questions}
          options={flow.options}
          answers={flow.answers}
          onAnswer={recordAnswer}
          onFinish={finishTest}
        />
      )}

      {screen === 'result' && flow.result && (
        <ResultScreen
          result={flow.result}
          onRestart={restart}
          sessionToken={flow.accessToken ?? undefined}
        />
      )}

      {/* 旅行答题直接复用 TestScreen——它只吃"题目 + 选项 + 答案回调"，
          对是哪套量表完全不感知，8 道旅行题原样能用，不需要另写一个答题页。 */}
      {screen === 'travel-test' && (
        <TestScreen
          questions={travelFlow.questions}
          options={travelFlow.options}
          answers={travelFlow.answers}
          onAnswer={recordTravelAnswer}
          onFinish={finishTravelTest}
        />
      )}

      {screen === 'travel-result' && travelFlow.profile && (
        <TravelResultScreen
          profile={travelFlow.profile}
          sessionToken={travelFlow.accessToken ?? undefined}
          onRestart={restart}
        />
      )}

      {screen === 'error' && error && (
        <div className="card">
          <ErrorBox title={error.title} message={error.message} onRetry={restart} />
        </div>
      )}
    </div>
  )
}
