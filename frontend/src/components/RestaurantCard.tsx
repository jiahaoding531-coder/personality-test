import { useState } from 'react'
import type { Restaurant } from '../travel/restaurantSearch'

export function RestaurantCard({ restaurant: r, onDismiss, onNavigate }: { restaurant: Restaurant; onDismiss: (id: string) => void; onNavigate?: () => void }) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  const image = r.imageUrl?.startsWith('https://') && r.imageUrl !== failedUrl ? r.imageUrl : null
  const navigation = new URLSearchParams({ position: `${r.longitude},${r.latitude}`, name: r.name, coordinate: 'gaode', callnative: '1', src: 'TravelMind' })
  return <article className="restaurant-card">
    <div className="restaurant-photo">{image ? <img src={image} alt={r.name} loading="lazy" referrerPolicy="no-referrer" onError={() => setFailedUrl(image)} /> : <span>暂无图片</span>}</div>
    <div className="restaurant-details">
      <h3>{r.name}</h3>
      <p className="restaurant-type">{r.type || '餐饮类别未知'}</p>
      <p>{r.address || '地址未知'}</p>
      <div className="restaurant-facts"><span>直线距离 {r.distanceMeters} 米</span><strong>{r.averageCost === null ? '人均未知' : `人均 ¥${r.averageCost}`}</strong></div>
      {r.openingHoursText && <p>营业时间：{r.openingHoursText}</p>}
      {r.telephone && <p>电话：{r.telephone}</p>}
      {!!r.tags?.length && <div className="restaurant-tags">{r.tags.map((tag, i) => <span key={i}>{tag}</span>)}</div>}
      <div className="restaurant-actions">
        <a href={`https://uri.amap.com/marker?${navigation}`} target="_blank" rel="noopener noreferrer"
          onClick={() => { try { onNavigate?.() } catch { /* 埋点失败不能取消原生导航。 */ } }}>导航过去</a>
        <button type="button" onClick={() => onDismiss(r.poiId)}>本次不再推荐</button>
      </div>
    </div>
  </article>
}
