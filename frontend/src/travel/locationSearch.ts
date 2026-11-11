/** 这是餐饮搜索中心，不是设备当前位置，也不是本地景点数据。 */
export interface LocationCandidate {
  poiId: string | null
  name: string
  address: string | null
  province: string | null
  city: string | null
  district: string | null
  latitude: number
  longitude: number
  coordinateSystem: 'GCJ02'
  source: 'AMAP_POI'
}
export interface LocationSearchScope { sessionId: number; sessionToken?: string }
export interface LocationSearchResponse { candidates: LocationCandidate[] }

export function isUsableLocation(candidate: LocationCandidate): boolean {
  return candidate != null && typeof candidate.name === 'string' && !!candidate.name.trim()
    && candidate.coordinateSystem === 'GCJ02' && candidate.source === 'AMAP_POI'
    && typeof candidate.latitude === 'number' && Number.isFinite(candidate.latitude) && Math.abs(candidate.latitude) <= 90
    && typeof candidate.longitude === 'number' && Number.isFinite(candidate.longitude) && Math.abs(candidate.longitude) <= 180
}
