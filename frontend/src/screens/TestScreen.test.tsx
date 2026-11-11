// @vitest-environment jsdom

import { cleanup, fireEvent, render } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { TestScreen } from './TestScreen'

afterEach(cleanup)

describe('TestScreen 提交锁定', () => {
  it('提交进行中忽略键盘快捷键，避免改变正在保存的答案快照', () => {
    const onAnswer = vi.fn()

    render(
      <TestScreen
        questions={[{
          id: 1,
          content: '旅行偏好问题',
          dimension: 'NATURE',
          dimensionLabel: '自然风光',
          sortOrder: 1,
        }]}
        options={[
          { value: 1, label: '不符合' },
          { value: 5, label: '很符合' },
        ]}
        answers={{ 1: 1 }}
        onAnswer={onAnswer}
        onFinish={vi.fn()}
        submitting
      />,
    )

    fireEvent.keyDown(document, { key: '5' })

    expect(onAnswer).not.toHaveBeenCalled()
  })
})
