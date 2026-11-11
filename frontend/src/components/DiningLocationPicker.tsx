import { useId, useLayoutEffect, useRef, useState } from 'react'
import { api } from '../api'
import { isUsableLocation, type LocationCandidate, type LocationSearchScope } from '../travel/locationSearch'

/** 草稿与已确认中心分离；输入变化、取消、卸载时立即作废旧响应。父组件按账号/会话重建。 */
export function DiningLocationPicker({ scope, onConfirm }: {
  scope: LocationSearchScope; onConfirm: (candidate: LocationCandidate) => void
}) {
  const id=useId()
  const [keyword,setKeyword]=useState('')
  const [candidates,setCandidates]=useState<LocationCandidate[]>([])
  const [selected,setSelected]=useState<LocationCandidate | null>(null)
  const [status,setStatus]=useState<'idle'|'loading'|'done'|'error'>('idle')
  const [error,setError]=useState('')
  const generation=useRef(0)
  const pending=useRef<AbortController | null>(null)
  const invalidate=()=>{ generation.current++; pending.current?.abort(); pending.current=null }
  useLayoutEffect(()=>()=>{generation.current++;pending.current?.abort()},[])

  const reset=()=>{ invalidate();setCandidates([]);setSelected(null);setStatus('idle');setError('') }
  const search=async()=>{
    const word=keyword.trim()
    if(!word || status==='loading') return
    invalidate()
    const sequence=generation.current
    const controller=new AbortController()
    pending.current=controller
    setStatus('loading');setCandidates([]);setSelected(null)
    const timeout=setTimeout(()=>controller.abort(),15000)
    try {
      const response=await api.searchLocations(scope,word,controller.signal)
      if(sequence!==generation.current) return
      if(!response || !Array.isArray(response.candidates)) throw new Error('地名搜索响应不完整，请重试。')
      setCandidates(response.candidates.filter(isUsableLocation));setStatus('done')
    } catch(reason) {
      if(sequence!==generation.current) return
      setStatus('error')
      setError(controller.signal.aborted ? '地名搜索超时，请重试。' : reason instanceof Error ? reason.message : '地名搜索失败，请重试。')
    } finally {
      clearTimeout(timeout)
      if(sequence===generation.current) pending.current=null
    }
  }

  return <section className="dining-location-picker" aria-label="选择餐饮搜索中心">
    <h3>按地名选择餐饮搜索中心</h3>
    <p className="drawer-note">仅用于附近餐饮，不改变设备当前位置或景点推荐位置。能搜索到地点，不代表当地已有个性化景点数据。</p>
    <form onSubmit={event=>{event.preventDefault();void search()}}>
      <label htmlFor={id}>搜索地点（仅餐饮）</label>
      <input id={id} value={keyword} maxLength={80} placeholder="例如：杭州西湖、平顶山人民公园"
        onChange={event=>{reset();setKeyword(event.target.value)}} />
      <button className="btn" type="submit" disabled={!keyword.trim() || status==='loading'}>搜索地名</button>
    </form>
    {status==='loading' && <p role="status">正在搜索真实地点…</p>}
    {status==='error' && <p role="alert">{error}</p>}
    {status==='done' && candidates.length===0 && <p role="status">没有找到可用坐标的地点，请补充城市或更换关键词。</p>}
    {candidates.length>0 && <fieldset><legend>选择真实地点（请核对地址与行政区）</legend>
      {candidates.map((candidate,index)=><label className="location-candidate" key={candidate.poiId ?? `${candidate.name}:${index}`}>
        <input type="radio" name={`${id}-candidate`} checked={selected===candidate} onChange={()=>setSelected(candidate)}/>
        <span><strong>{candidate.name}</strong><small>{[candidate.province,candidate.city,candidate.district].filter(Boolean).join(' · ') || '行政区未知'}</small>
          <small>{candidate.address || '地址未提供'}</small></span>
      </label>)}
    </fieldset>}
    <div className="location-picker-actions">
      <button className="btn btn-primary" type="button" disabled={!selected || status!=='done'} onClick={()=>{if(selected) onConfirm({...selected})}}>设为餐饮搜索中心</button>
      <button className="btn" type="button" onClick={()=>{reset();setKeyword('')}}>取消选择</button>
    </div>
  </section>
}
