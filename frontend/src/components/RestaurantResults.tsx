import { RestaurantCard } from './RestaurantCard'
import type { RestaurantState, RestaurantCandidate } from '../travel/restaurantSearch'

export function RestaurantResults({ state, onNext, onDismiss, onLocation, onNavigate }: {
  state: RestaurantState; onNext: () => void; onDismiss: (id: string) => void; onLocation: () => void; onNavigate?: (restaurant: RestaurantCandidate) => void
}) {
  return <section className="restaurant-results" aria-label="附近餐饮">
    <header><h2>附近吃什么</h2><p>来自高德的餐饮信息 · 按直线距离排列</p></header>
    <p>本轮餐饮按最远距离搜索，暂不按预算、可用时间或安静程度筛选。</p>
    {state.loading && <p role="status">正在寻找附近餐饮…</p>}
    {state.error && <div role="alert" className="restaurant-error"><p>{state.error}</p><button type="button" onClick={onNext}>重试餐饮搜索</button><button type="button" onClick={onLocation}>位置设置</button></div>}
    {!state.loading && !state.error && !state.visible.length && <p role="status">{state.page <= 1 ? '附近暂无符合距离条件的餐饮。' : '暂无更多'}</p>}
    <div className="restaurant-list">{state.visible.map(r => <RestaurantCard key={r.poiId} restaurant={r} onDismiss={onDismiss} onNavigate={() => onNavigate?.(r)} />)}</div>
    {state.exhausted && state.visible.length > 0 && <p role="status">暂无更多</p>}
    <button className="restaurant-next" type="button" disabled={state.loading || state.exhausted} onClick={onNext}>换一批餐饮</button>
  </section>
}
