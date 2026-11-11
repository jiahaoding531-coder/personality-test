// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { RestaurantCard } from './RestaurantCard'
import { RestaurantResults } from './RestaurantResults'
import type { Restaurant } from '../travel/restaurantSearch'
afterEach(cleanup)
const restaurant: Restaurant = { poiId:'B1', name:'测试餐饮', type:'餐饮服务;快餐厅', typeCode:'050300', address:'测试路', latitude:33.7, longitude:113.3, distanceMeters:120, averageCost:null, openingHoursText:null, telephone:null, imageUrl:null, rating:null, tags:null }
it('真实字段和未知人均，导航使用高德坐标',()=>{
  render(<RestaurantCard restaurant={restaurant} onDismiss={vi.fn()} />)
  expect(screen.getByText('人均未知')).toBeTruthy()
  expect(screen.getByText('直线距离 120 米')).toBeTruthy()
  expect(screen.getByText('暂无图片')).toBeTruthy()
  const link=screen.getByRole('link',{name:'导航过去'})
  expect(link.getAttribute('href')).toContain('position=113.3%2C33.7')
  expect(link.getAttribute('href')).toContain('coordinate=gaode')
  expect(screen.queryByText(/正在营业/)).toBeNull()
})
it('记录失败不取消原生导航也不会产生第二次事件',()=>{
  const navigate=vi.fn(()=>{ throw new Error('发送失败') })
  render(<RestaurantCard restaurant={restaurant} onDismiss={vi.fn()} onNavigate={navigate} />)
  const link=screen.getByRole('link',{name:'导航过去'})
  expect(fireEvent.click(link)).toBe(true)
  expect(navigate).toHaveBeenCalledOnce()
  expect(link.getAttribute('href')).toContain('uri.amap.com')
})
it('HTTPS图片失败显示占位，并独立排除地点',()=>{
  const dismiss=vi.fn()
  render(<RestaurantCard restaurant={{...restaurant,imageUrl:'https://example.com/photo.jpg',averageCost:0,openingHoursText:'10:00-20:00'}} onDismiss={dismiss} />)
  fireEvent.error(screen.getByRole('img'))
  expect(screen.getByText('暂无图片')).toBeTruthy()
  expect(screen.getByText('人均 ¥0')).toBeTruthy()
  fireEvent.click(screen.getByRole('button',{name:'本次不再推荐'}))
  expect(dismiss).toHaveBeenCalledWith('B1')
})
it('HTTP图片不加载',()=>{
  render(<RestaurantCard restaurant={{...restaurant,imageUrl:'http://example.com/photo.jpg'}} onDismiss={vi.fn()} />)
  expect(screen.queryByRole('img')).toBeNull()
})
it('错误状态和重试入口可操作',()=>{
  const next=vi.fn()
  render(<RestaurantResults state={{page:0,candidates:[],visible:[],shown:[],excluded:[],loading:false,exhausted:false,error:'高德权限异常',searchSessionId:null,contextExpired:false,lastSource:null}} onNext={next} onDismiss={vi.fn()} onLocation={vi.fn()} />)
  expect(screen.getByRole('alert').textContent).toContain('高德权限异常')
  fireEvent.click(screen.getByRole('button',{name:'重试餐饮搜索'})); expect(next).toHaveBeenCalledOnce()
})
