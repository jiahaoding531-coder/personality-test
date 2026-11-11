export type CoordinateSystem = 'WGS84' | 'GCJ02' | 'UNKNOWN'

export interface LocationState {
  latitude: string
  longitude: string
  coordinateSystem: CoordinateSystem
}

/** 浏览器定位按 WGS84 处理；格式化仅用于界面，不能提前损失请求精度。 */
export function fromBrowserPosition(coords: Pick<GeolocationCoordinates, 'latitude' | 'longitude'>): LocationState {
  return { latitude: String(coords.latitude), longitude: String(coords.longitude), coordinateSystem: 'WGS84' }
}

/** 旧测试点和未注明来源的手动输入，需要确认后才能用于高德餐饮搜索。 */
export function fromUnconfirmedPosition(latitude: string, longitude: string): LocationState {
  return { latitude, longitude, coordinateSystem: 'UNKNOWN' }
}
