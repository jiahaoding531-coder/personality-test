import { describe, expect, it } from 'vitest'
import { fromBrowserPosition, fromUnconfirmedPosition } from './travelLocation'

describe('位置来源契约', () => {
  it('浏览器坐标保留原始精度并标记 WGS84', () => {
    const value = fromBrowserPosition({ latitude: 33.735012345, longitude: 113.307712345 })
    expect(value).toEqual({ latitude: '33.735012345', longitude: '113.307712345', coordinateSystem: 'WGS84' })
  })
  it('手动或旧测试坐标不能继承之前 GPS 的坐标系', () => {
    const value = fromUnconfirmedPosition('30.2420', '120.1400')
    expect(value.coordinateSystem).toBe('UNKNOWN')
    expect(value.latitude).toBe('30.2420')
  })
})
