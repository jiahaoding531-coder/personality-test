// @vitest-environment jsdom

import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import App from './App'
import { ApiError, api } from './api'
import type {
  QuestionsResponse,
  SessionResultResponse,
  SessionResponse,
  TravelProfileResponse,
  UserResponse,
} from './types'

vi.mock('./screens/TravelResultScreen', () => ({
  TravelResultScreen: ({ profile }: { profile: TravelProfileResponse }) => (
    <div>对话页面，会话 {profile.sessionId}</div>
  ),
}))

vi.mock('./screens/ResultScreen', () => ({
  ResultScreen: ({ result }: { result: SessionResultResponse }) => (
    <div>人格结果，会话 {result.sessionId}</div>
  ),
}))

const USER_A: UserResponse = {
  id: 1,
  username: 'alice',
  createdAt: '2026-09-19T00:00:00Z',
}

const USER_B: UserResponse = {
  id: 2,
  username: 'bob',
  createdAt: '2026-09-19T00:00:00Z',
}

function session(sessionId: number): SessionResponse {
  return {
    sessionId,
    accessToken: `token-${sessionId}`,
    status: 'IN_PROGRESS',
    createdAt: '2026-09-19T00:00:00Z',
  }
}

function profile(sessionId: number): TravelProfileResponse {
  return {
    sessionId,
    status: 'SUBMITTED',
    createdAt: '2026-09-19T00:00:00Z',
    submittedAt: '2026-09-19T00:01:00Z',
    scale: 'TRAVEL',
    reused: true,
    dimensions: [{ key: 'NATURE', name: '自然风光', score: 80 }],
  }
}

const QUESTION_BANK: QuestionsResponse = {
  options: [
    { value: 1, label: '不符合' },
    { value: 5, label: '很符合' },
  ],
  questions: [
    { id: 101, content: '旅行时我喜欢自然风光', dimension: 'NATURE', dimensionLabel: '自然风光', sortOrder: 1 },
    { id: 102, content: '旅行时我喜欢当地文化', dimension: 'CULTURE', dimensionLabel: '人文历史', sortOrder: 2 },
  ],
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

beforeEach(() => {
  vi.spyOn(api, 'getCurrentUser').mockResolvedValue(null)
  vi.spyOn(api, 'createTravelSession').mockResolvedValue(session(10))
  vi.spyOn(api, 'getTravelProfile').mockResolvedValue(profile(10))
  vi.spyOn(api, 'getTravelQuestions').mockResolvedValue(QUESTION_BANK)
  vi.spyOn(api, 'saveTravelAnswers').mockResolvedValue({ sessionId: 10, savedCount: 2 })
  vi.spyOn(api, 'submitTravel').mockResolvedValue(profile(10))
  vi.spyOn(api, 'logout').mockResolvedValue(undefined)
  vi.spyOn(api, 'login').mockResolvedValue(USER_B)
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: vi.fn().mockReturnValue({ matches: false }),
  })
  window.history.replaceState({}, '', '/')
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('首次使用与二次登录分流', () => {
  it('确认登录状态期间只显示统一加载态，不闪现测试页或对话页', () => {
    vi.mocked(api.getCurrentUser).mockReturnValue(new Promise(() => {}))

    render(<App />)

    expect(screen.getByText('正在确认你的 TravelMind 状态…')).toBeTruthy()
    expect(screen.queryByText('旅行时我喜欢自然风光')).toBeNull()
    expect(screen.queryByText(/对话页面/)).toBeNull()
  })

  it('老用户刷新后复用服务端旅行画像并直接进入对话，不查询人格测试', async () => {
    vi.mocked(api.getCurrentUser).mockResolvedValue(USER_A)
    const personalityQuestions = vi.spyOn(api, 'getQuestions')

    render(<App />)

    expect(await screen.findByText('对话页面，会话 10')).toBeTruthy()
    expect(api.getTravelQuestions).not.toHaveBeenCalled()
    expect(personalityQuestions).not.toHaveBeenCalled()
  })

  it('只有明确的 TRAVEL_PROFILE_REQUIRED 才进入旅行偏好测试', async () => {
    vi.mocked(api.getCurrentUser).mockResolvedValue(USER_A)
    vi.mocked(api.getTravelProfile).mockRejectedValue(
      new ApiError('尚未建立旅行画像', 404, [], 'TRAVEL_PROFILE_REQUIRED'),
    )

    render(<App />)

    expect(await screen.findByText('旅行时我喜欢自然风光')).toBeTruthy()
    expect(api.getTravelQuestions).toHaveBeenCalledTimes(1)
  })

  it('普通 404 不被误判为未测试，失败页可重试', async () => {
    vi.mocked(api.getCurrentUser).mockResolvedValue(USER_A)
    vi.mocked(api.getTravelProfile)
      .mockRejectedValueOnce(new ApiError('测试会话不存在', 404))
      .mockResolvedValueOnce(profile(11))
    vi.mocked(api.createTravelSession)
      .mockResolvedValueOnce(session(10))
      .mockResolvedValueOnce(session(11))

    render(<App />)

    expect(await screen.findByText('测试会话不存在')).toBeTruthy()
    expect(api.getTravelQuestions).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByText('对话页面，会话 11')).toBeTruthy()
  })

  it('StrictMode 重复执行副作用时只创建一个旅行会话', async () => {
    vi.mocked(api.getCurrentUser).mockResolvedValue(USER_A)

    render(<StrictMode><App /></StrictMode>)

    expect(await screen.findByText('对话页面，会话 10')).toBeTruthy()
    expect(api.createTravelSession).toHaveBeenCalledTimes(1)
  })
})

describe('账号与请求竞态', () => {
  it('注销后换账号登录时，旧账号的晚到响应不能覆盖新账号', async () => {
    const oldProfile = deferred<TravelProfileResponse>()
    vi.mocked(api.getCurrentUser).mockResolvedValue(USER_A)
    vi.mocked(api.createTravelSession)
      .mockResolvedValueOnce(session(10))
      .mockResolvedValueOnce(session(20))
    vi.mocked(api.getTravelProfile)
      .mockReturnValueOnce(oldProfile.promise)
      .mockResolvedValueOnce(profile(20))

    render(<App />)

    await screen.findAllByText('alice')
    fireEvent.click(screen.getAllByRole('button', { name: '退出登录' })[0]!)
    await screen.findByRole('heading', { name: '登录' })

    fireEvent.change(screen.getByLabelText('用户名'), { target: { value: 'bob' } })
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'Passw0rd!' } })
    fireEvent.click(screen.getByRole('button', { name: '登录' }))

    expect(await screen.findByText('对话页面，会话 20')).toBeTruthy()
    await act(async () => {
      oldProfile.resolve(profile(10))
      await oldProfile.promise
    })

    expect(screen.queryByText('对话页面，会话 10')).toBeNull()
    expect(screen.getAllByText('bob').length).toBeGreaterThan(0)
  })

  it('历史人格结果在注销换号后才返回时，不得覆盖新账号对话', async () => {
    const oldResult = deferred<SessionResultResponse>()
    window.history.replaceState({}, '', '/?result=99')
    vi.mocked(api.getCurrentUser).mockResolvedValue(USER_A)
    vi.spyOn(api, 'getResult').mockReturnValue(oldResult.promise)

    render(<App />)

    await screen.findAllByText('alice')
    fireEvent.click(screen.getAllByRole('button', { name: '退出登录' })[0]!)
    await screen.findByRole('heading', { name: '登录' })
    fireEvent.change(screen.getByLabelText('用户名'), { target: { value: 'bob' } })
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'Passw0rd!' } })
    fireEvent.click(screen.getByRole('button', { name: '登录' }))
    expect(await screen.findByText('对话页面，会话 10')).toBeTruthy()

    await act(async () => {
      oldResult.resolve({
        sessionId: 99,
        status: 'SUBMITTED',
        createdAt: '2026-09-19T00:00:00Z',
        submittedAt: '2026-09-19T00:01:00Z',
        dimensions: [],
        disclaimer: '仅供参考',
      })
      await oldResult.promise
    })

    expect(screen.queryByText('人格结果，会话 99')).toBeNull()
    expect(screen.getByText('对话页面，会话 10')).toBeTruthy()
  })

  it('旅行画像晚到时不能覆盖用户刚打开的人格探索', async () => {
    const oldProfile = deferred<TravelProfileResponse>()
    vi.mocked(api.getCurrentUser).mockResolvedValue(USER_A)
    vi.mocked(api.getTravelProfile).mockReturnValue(oldProfile.promise)
    vi.spyOn(api, 'createSession').mockResolvedValue(session(30))
    vi.spyOn(api, 'getQuestions').mockResolvedValue({
      ...QUESTION_BANK,
      questions: [{
        id: 201,
        content: '这是人格探索问题',
        dimension: 'OPENNESS',
        dimensionLabel: '开放性',
        sortOrder: 1,
      }],
    })

    render(<App />)

    await screen.findAllByText('alice')
    fireEvent.click(screen.getAllByRole('button', { name: '人格探索' })[0]!)
    expect(await screen.findByText('这是人格探索问题')).toBeTruthy()

    await act(async () => {
      oldProfile.resolve(profile(10))
      await oldProfile.promise
    })

    expect(screen.getByText('这是人格探索问题')).toBeTruthy()
    expect(screen.queryByText(/对话页面/)).toBeNull()
  })

  it('注销等待期间启动的请求，在注销完成后也不能覆盖登录页', async () => {
    const logout = deferred<void>()
    const duringLogoutProfile = deferred<TravelProfileResponse>()
    vi.mocked(api.getCurrentUser).mockResolvedValue(USER_A)
    vi.mocked(api.logout).mockReturnValue(logout.promise)
    vi.mocked(api.createTravelSession)
      .mockResolvedValueOnce(session(10))
      .mockResolvedValueOnce(session(11))
    vi.mocked(api.getTravelProfile)
      .mockResolvedValueOnce(profile(10))
      .mockReturnValueOnce(duringLogoutProfile.promise)

    render(<App />)

    expect(await screen.findByText('对话页面，会话 10')).toBeTruthy()
    fireEvent.click(screen.getAllByRole('button', { name: '退出登录' })[0]!)
    fireEvent.click(screen.getAllByRole('button', { name: '对话' })[0]!)

    await act(async () => {
      logout.resolve()
      await logout.promise
    })
    expect(screen.getByRole('heading', { name: '登录' })).toBeTruthy()

    await act(async () => {
      duringLogoutProfile.resolve(profile(11))
      await duringLogoutProfile.promise
    })
    expect(screen.getByRole('heading', { name: '登录' })).toBeTruthy()
    expect(screen.queryByText('对话页面，会话 11')).toBeNull()
  })

  it('快速重复点击退出只发送一次注销请求', async () => {
    const logout = deferred<void>()
    vi.mocked(api.getCurrentUser).mockResolvedValue(USER_A)
    vi.mocked(api.logout).mockReturnValue(logout.promise)

    render(<App />)

    expect(await screen.findByText('对话页面，会话 10')).toBeTruthy()
    const logoutButton = screen.getByRole('button', { name: '退出登录' })
    fireEvent.click(logoutButton)
    fireEvent.click(logoutButton)

    expect(api.logout).toHaveBeenCalledTimes(1)

    await act(async () => {
      logout.resolve()
      await logout.promise
    })
  })
})

describe('旅行偏好提交', () => {
  it('保存失败时保留答案和当前题，允许原地重试且不会假装完成', async () => {
    vi.mocked(api.getCurrentUser).mockResolvedValue(USER_A)
    vi.mocked(api.getTravelProfile).mockRejectedValue(
      new ApiError('尚未建立旅行画像', 404, [], 'TRAVEL_PROFILE_REQUIRED'),
    )
    vi.mocked(api.saveTravelAnswers)
      .mockRejectedValueOnce(new ApiError('保存失败，请稍后再试', 500))
      .mockResolvedValueOnce({ sessionId: 10, savedCount: 2 })

    render(<App />)

    await screen.findByText('旅行时我喜欢自然风光')
    fireEvent.click(screen.getByRole('button', { name: '很符合' }))
    await screen.findByText('旅行时我喜欢当地文化')
    fireEvent.click(screen.getByRole('button', { name: '不符合' }))
    fireEvent.click(screen.getByRole('button', { name: '查看结果' }))

    expect(await screen.findByText('保存失败，请稍后再试')).toBeTruthy()
    expect(screen.getByText('旅行时我喜欢当地文化')).toBeTruthy()
    expect(screen.getByText('已答 2 / 2')).toBeTruthy()
    expect(screen.queryByText(/对话页面/)).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '重新提交' }))
    expect(await screen.findByText('对话页面，会话 10')).toBeTruthy()
    expect(api.saveTravelAnswers).toHaveBeenCalledTimes(2)
  })
})
