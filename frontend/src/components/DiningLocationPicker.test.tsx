// @vitest-environment jsdom
import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { ApiError, api } from '../api'
import { DiningLocationPicker } from './DiningLocationPicker'
import type { LocationSearchResponse } from '../travel/locationSearch'

const valid={poiId:'public',name:'人民公园',address:'公园路1号',province:'河南省',city:'平顶山市',district:'新华区',
  latitude:33.735,longitude:113.3077,coordinateSystem:'GCJ02' as const,source:'AMAP_POI' as const}
const scope={sessionId:42,sessionToken:'test-token'}
afterEach(()=>{cleanup();vi.restoreAllMocks();vi.useRealTimers()})
function search(word='人民公园') {
  fireEvent.change(screen.getByRole('textbox',{name:'搜索地点（仅餐饮）'}),{target:{value:word}})
  fireEvent.click(screen.getByRole('button',{name:'搜索地名'}))
}
it.each([
  ['AMAP_PERMISSION_DENIED','高德服务权限或密钥配置异常'],
  ['AMAP_QUOTA_EXCEEDED','高德服务调用额度已达限制'],
  ['AMAP_NETWORK_ERROR','暂时无法连接高德服务'],
])('%s 不产生假候选且能重试',async(code,message)=>{
  const fetch=vi.spyOn(api,'searchLocations').mockRejectedValueOnce(new ApiError(message,502,[],code))
    .mockResolvedValue({candidates:[valid]})
  const confirm=vi.fn()
  render(<DiningLocationPicker scope={scope} onConfirm={confirm}/> )
  search()
  expect((await screen.findByRole('alert')).textContent).toContain(message)
  expect(screen.queryAllByRole('radio')).toHaveLength(0)
  fireEvent.click(screen.getByRole('button',{name:'设为餐饮搜索中心'}))
  expect(confirm).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button',{name:'搜索地名'}))
  await screen.findByRole('radio')
  expect(fetch).toHaveBeenCalledTimes(2)
})
it('无结果和缺坐标候选均不能确认，不能把 WGS84 冒充搜索 POI',async()=>{
  vi.spyOn(api,'searchLocations').mockResolvedValue({candidates:[{...valid,latitude:NaN},{...valid,coordinateSystem:'WGS84'}] as unknown as LocationSearchResponse['candidates']})
  render(<DiningLocationPicker scope={scope} onConfirm={vi.fn()}/> )
  search()
  expect(await screen.findByText(/没有找到可用坐标/)).toBeTruthy()
  expect(screen.queryAllByRole('radio')).toHaveLength(0)
})
it('快速搜索先后关键词、取消和卸载后，旧成功或旧失败均不能复活',async()=>{
  let old!:(r:LocationSearchResponse)=>void
  let late!:(r:LocationSearchResponse)=>void
  vi.spyOn(api,'searchLocations').mockImplementationOnce(()=>new Promise(r=>{old=r}))
    .mockResolvedValueOnce({candidates:[{...valid,name:'最新地点'}]})
    .mockImplementationOnce(()=>new Promise(r=>{late=r}))
  const view=render(<StrictMode><DiningLocationPicker scope={scope} onConfirm={vi.fn()}/></StrictMode>)
  search('旧地名'); search('新地名')
  await screen.findByText('最新地点')
  await act(async()=>old({candidates:[{...valid,name:'过期地点'}]}))
  expect(screen.queryByText('过期地点')).toBeNull()
  search('取消中的地名')
  fireEvent.click(screen.getByRole('button',{name:'取消选择'}))
  await act(async()=>late({candidates:[{...valid,name:'取消的地点'}]}))
  expect(screen.queryAllByRole('radio')).toHaveLength(0)
  view.unmount()
})
it('超时可重试且不改变确认状态',async()=>{
  vi.useFakeTimers()
  vi.spyOn(api,'searchLocations').mockImplementation((_scope,_keyword,signal)=>new Promise((_resolve,reject)=>{
    signal!.addEventListener('abort',()=>reject(new Error('中止')))
  }))
  const confirm=vi.fn()
  render(<DiningLocationPicker scope={scope} onConfirm={confirm}/> )
  search()
  await act(()=>vi.advanceTimersByTimeAsync(15000))
  expect(screen.getByRole('alert').textContent).toContain('超时')
  expect(confirm).not.toHaveBeenCalled()
})
