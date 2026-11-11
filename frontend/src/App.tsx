import { useCallback, useEffect, useRef, useState } from 'react'

import { ApiError, api } from './api'
import { AppShell, type AppNavigationItem } from './components/AppShell'
import { ErrorBox, Loading } from './components/Status'
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
  const [screen, setScreen] = useState<Screen>('loading')
  const [loadingText, setLoadingText] = useState('正在确认你的 TravelMind 状态…')
  const [flow, setFlow] = useState<Flow>(EMPTY_FLOW)
  const [travelFlow, setTravelFlow] = useState<TravelFlow>(EMPTY_TRAVEL_FLOW)
  const [error, setError] = useState<{
    title: string
    message: string
    retry?: () => void
  } | null>(null)
  const [travelSubmission, setTravelSubmission] = useState<{
    busy: boolean
    error: string | null
  }>({ busy: false, error: null })
  /** 每次改变账号或主流程都递增。晚到的旧响应只能读到旧编号，因此无法覆盖新页面。 */
  const routeRequestId = useRef(0)
  /** React StrictMode 会重复执行 effect；这个门闩保证启动流程只发起一次。 */
  const bootstrapStarted = useRef(false)
  /** 注销只能有一个进行中的请求，防止旧注销回调清掉刚登录的新账号。 */
  const logoutInFlight = useRef(false)

  /**
   * 当前登录用户。null = 未登录。
   *
   * `undefined` 表示"还没查过"——这个区分很重要：
   * 如果初始值是 null，界面会在启动的一瞬间显示"未登录"，
   * 然后 /me 返回后才切换到已登录状态，用户会看到一次闪烁。
   */
  const [user, setUser] = useState<UserResponse | null | undefined>(undefined)

  /** 把任意异常转成可展示的错误屏。 */
  const fail = useCallback((title: string, e: unknown, retry?: () => void) => {
    setError({ title, message: e instanceof Error ? e.message : String(e), retry })
    setScreen('error')
  }, [])

  /** 打开某次历史结果（也用于分享链接 /?result=5）。 */
  const openResult = useCallback(
    async (sessionId: number) => {
      const requestId = ++routeRequestId.current
      const isLatest = () => routeRequestId.current === requestId
      setLoadingText('正在载入画像…')
      setScreen('loading')
      try {
        const result = await api.getResult(sessionId)
        if (!isLatest()) return
        setFlow({ ...EMPTY_FLOW, sessionId: result.sessionId, result })
        setScreen('result')
      } catch (e: unknown) {
        if (!isLatest()) return
        fail('无法载入结果', e)
      }
    },
    [fail],
  )

  /** 开始新一轮测试：建会话 + 取题目，两个请求并发发出。 */
  const startTest = useCallback(async () => {
    const requestId = ++routeRequestId.current
    const isLatest = () => routeRequestId.current === requestId
    setLoadingText('正在准备题目…')
    setScreen('loading')
    try {
      // 这两个请求互不依赖，用 Promise.all 并发发出，
      // 总耗时约等于较慢的那个，而不是两个相加。
      //
      // 如果已登录，后端会自动把这次会话关联到当前用户——
      // 不需要前端传任何用户标识，身份从服务端会话里取。
      const [session, bank] = await Promise.all([api.createSession(), api.getQuestions()])
      if (!isLatest()) return
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
      if (!isLatest()) return
      fail('无法开始测试', e)
    }
  }, [fail])

  /** 提交并计分。 */
  const finishTest = useCallback(async () => {
    const { sessionId, accessToken, questions, answers } = flow
    if (sessionId === null) return

    const requestId = ++routeRequestId.current
    const isLatest = () => routeRequestId.current === requestId
    setLoadingText('正在计算结果…')
    setScreen('loading')
    try {
      const payload = questions.map((q) => ({ questionId: q.id, score: answers[q.id] ?? 0 }))
      // 带上令牌——匿名测试时服务端只认它
      await api.saveAnswers(sessionId, payload, accessToken ?? undefined)
      if (!isLatest()) return
      const result = await api.submit(sessionId, accessToken ?? undefined)
      if (!isLatest()) return
      setFlow((f) => ({ ...f, result }))
      setScreen('result')
    } catch (e: unknown) {
      if (!isLatest()) return
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
    const requestId = ++routeRequestId.current
    const isLatest = () => routeRequestId.current === requestId

    setError(null)
    setTravelSubmission({ busy: false, error: null })
    setLoadingText('正在读取你的旅行画像…')
    setScreen('loading')
    try {
      const session = await api.createTravelSession()
      if (!isLatest()) return
      const base = {
        sessionId: session.sessionId,
        accessToken: session.accessToken,
        questions: [] as Question[],
        options: [] as ScaleOption[],
        answers: {},
      }

      // 先试历史画像。只有后端明确返回稳定业务码时，才代表这个用户需要首次测试。
      // 普通 404 也可能是会话不存在或无权访问，绝不能悄悄当成“新用户”。
      try {
        const reused = await api.getTravelProfile(session.sessionId, session.accessToken)
        if (!isLatest()) return
        setTravelFlow({ ...base, profile: reused })
        setScreen('travel-result')
        return
      } catch (e: unknown) {
        const needsProfile = e instanceof ApiError
          && e.status === 404
          && e.code === 'TRAVEL_PROFILE_REQUIRED'
        if (!needsProfile) throw e
      }

      const bank = await api.getTravelQuestions()
      if (!isLatest()) return
      setTravelFlow({
        ...base,
        questions: bank.questions,
        options: bank.options,
        profile: null,
      })
      setScreen('travel-test')
    } catch (e: unknown) {
      if (!isLatest()) return
      fail('无法进入 TravelMind', e, () => {
        void startTravelTest()
      })
    }
  }, [fail])

  /**
   * 启动分流：先确认身份，再由服务端旅行画像决定进入对话还是首次测试。
   * 人格测试完全不参与这里的判断。
   */
  const bootstrap = useCallback(async () => {
    const requestId = ++routeRequestId.current
    const isLatest = () => routeRequestId.current === requestId
    setLoadingText('正在确认你的 TravelMind 状态…')
    setScreen('loading')
    try {
      const currentUser = await api.getCurrentUser()
      if (!isLatest()) return
      setUser(currentUser)

      const sid = new URLSearchParams(window.location.search).get('result')
      if (sid) {
        await openResult(Number(sid))
        return
      }

      if (currentUser) {
        await startTravelTest()
      } else {
        setScreen('auth')
      }
    } catch (e: unknown) {
      if (!isLatest()) return
      fail('无法确认登录状态', e, () => {
        void bootstrap()
      })
    }
  }, [fail, openResult, startTravelTest])

  useEffect(() => {
    if (bootstrapStarted.current) return
    bootstrapStarted.current = true
    void bootstrap()
  }, [bootstrap])

  /** 提交旅行测试，得到 8 维画像。 */
  const finishTravelTest = useCallback(async () => {
    const { sessionId, accessToken, questions, answers } = travelFlow
    if (sessionId === null) return

    const requestId = ++routeRequestId.current
    const isLatest = () => routeRequestId.current === requestId
    setTravelSubmission({ busy: true, error: null })
    try {
      const payload = questions.map((q) => ({ questionId: q.id, score: answers[q.id] ?? 0 }))
      await api.saveTravelAnswers(sessionId, payload, accessToken ?? undefined)
      if (!isLatest()) return
      const profile = await api.submitTravel(sessionId, accessToken ?? undefined)
      if (!isLatest()) return
      setTravelFlow((f) => ({ ...f, profile }))
      setTravelSubmission({ busy: false, error: null })
      setScreen('travel-result')
    } catch (e: unknown) {
      if (!isLatest()) return
      setTravelSubmission({
        busy: false,
        error: e instanceof Error ? e.message : String(e),
      })
    }
  }, [travelFlow])

  const restart = useCallback(() => {
    routeRequestId.current += 1
    setFlow(EMPTY_FLOW)
    setTravelFlow(EMPTY_TRAVEL_FLOW)
    setTravelSubmission({ busy: false, error: null })
    setScreen('home')
  }, [])

  const handleLogout = useCallback(async () => {
    if (logoutInFlight.current) return
    logoutInFlight.current = true
    routeRequestId.current += 1
    setUser(undefined)
    setLoadingText('正在安全退出…')
    setScreen('loading')
    try {
      await api.logout()
    } catch {
      // 注销失败也要把本地状态清掉——否则用户会卡在"看起来已登录
      // 但服务端已经不认"的状态里。服务端会话可能已经过期了。
    }
    // 注销等待期间用户仍可能点了导航并启动新请求；完成时再次递增，
    // 让这段窗口里发出的请求也全部失效。
    routeRequestId.current += 1
    setUser(null)
    setFlow(EMPTY_FLOW)
    setTravelFlow(EMPTY_TRAVEL_FLOW)
    setTravelSubmission({ busy: false, error: null })
    setScreen('auth')
    logoutInFlight.current = false
  }, [])

  const openHistory = useCallback(() => {
    routeRequestId.current += 1
    setScreen('history')
  }, [])

  const openAuth = useCallback(() => {
    routeRequestId.current += 1
    setScreen('auth')
  }, [])

  const openHome = useCallback(() => {
    routeRequestId.current += 1
    setScreen('home')
  }, [])

  const navigation: AppNavigationItem[] = [
    {
      id: 'conversation',
      label: '对话',
      icon: 'chat',
      onSelect: startTravelTest,
    },
    {
      id: 'personality',
      label: '人格探索',
      icon: 'personality',
      onSelect: startTest,
    },
    ...(user
      ? [{
          id: 'history',
          label: '人格测试记录',
          icon: 'history' as const,
          onSelect: openHistory,
        }]
      : []),
  ]

  const activeNavigation = screen === 'travel-test' || screen === 'travel-result'
    ? 'conversation'
    : screen === 'test' || screen === 'result'
      ? 'personality'
      : screen === 'history'
        ? 'history'
        : null

  return (
    <AppShell
      activeItem={activeNavigation}
      navigation={navigation}
      user={user}
      onLogin={openAuth}
      onLogout={handleLogout}
    >

      {screen === 'home' && (
        <HomeScreen onStart={startTest} onStartTravel={startTravelTest} />
      )}

      {screen === 'auth' && (
        <AuthScreen
          onSuccess={(u) => {
            setUser(u)
            void startTravelTest()
          }}
          onBack={openHome}
        />
      )}

      {screen === 'history' && (
        <HistoryScreen onOpen={openResult} onBack={openHome} />
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
          submitting={travelSubmission.busy}
          submitError={travelSubmission.error}
        />
      )}

      {screen === 'travel-result' && travelFlow.profile && (
        <TravelResultScreen
          key={`${user?.id ?? 'anonymous'}-${travelFlow.profile.sessionId}`}
          userId={user?.id ?? null}
          profile={travelFlow.profile}
          sessionToken={travelFlow.accessToken ?? undefined}
          onRestart={restart}
        />
      )}

      {screen === 'error' && error && (
        <div className="card">
          <ErrorBox title={error.title} message={error.message} onRetry={error.retry ?? restart} />
        </div>
      )}
    </AppShell>
  )
}
