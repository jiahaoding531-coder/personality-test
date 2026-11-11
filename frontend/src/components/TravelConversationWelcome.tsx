export interface TravelQuickNeed {
  id: string
  icon: string
  label: string
  active: boolean
}

export interface TravelContextSummary {
  locationLabel: string | null
  durationMinutes: number | null
  maxDistanceKm: number | null
  budgetMax: number | null
}

export function TravelConversationWelcome({
  context,
  quickNeeds,
  reusedProfile,
  busy,
  onSelectNeed,
  onOpenPreferences,
}: {
  context: TravelContextSummary
  quickNeeds: TravelQuickNeed[]
  reusedProfile: boolean
  busy: boolean
  onSelectNeed: (id: string) => void
  onOpenPreferences: () => void
}) {
  return (
    <section className="travel-welcome" aria-labelledby="travel-welcome-title">
      <div className="travel-welcome-copy">
        <span className="travel-eyebrow">此刻，为你出发</span>
        <h1 id="travel-welcome-title">现在想去哪里？</h1>
        <p>告诉我你想做什么，或者直接说出此刻的状态，我会结合位置和旅行偏好继续筛选。</p>
        {reusedProfile && <small>已沿用你上次保存的旅行偏好，不需要重新答题。</small>}
      </div>

      <TripContext context={context} onOpenPreferences={onOpenPreferences} />

      <div className="quick-needs" role="region" aria-label="快捷旅行需求">
        <span className="quick-needs-label">快捷告诉我</span>
        <div className="quick-needs-grid">
          {quickNeeds.map((need) => (
            <button
              className={`quick-need${need.active ? ' is-active' : ''}`}
              type="button"
              key={need.id}
              disabled={busy}
              aria-pressed={need.active}
              onClick={() => onSelectNeed(need.id)}
            >
              <span aria-hidden="true">{need.icon}</span>
              <b>{need.label}</b>
            </button>
          ))}
        </div>
      </div>
    </section>
  )
}

function TripContext({
  context,
  onOpenPreferences,
}: {
  context: TravelContextSummary
  onOpenPreferences: () => void
}) {
  return (
    <section className="trip-context" aria-labelledby="trip-context-title">
      <header>
        <div>
          <span>当前条件</span>
          <h2 id="trip-context-title">本次出行</h2>
        </div>
        <button type="button" onClick={onOpenPreferences}>调整</button>
      </header>
      <div className="trip-context-grid">
        <ContextItem icon="⌖" label="位置" value={context.locationLabel ?? '位置待确认'} unknown={!context.locationLabel} />
        <ContextItem icon="◷" label="可用时间" value={formatDuration(context.durationMinutes)} unknown={context.durationMinutes === null} />
        <ContextItem icon="↗" label="最远距离" value={context.maxDistanceKm === null ? '距离未设置' : `${formatNumber(context.maxDistanceKm)} 公里`} unknown={context.maxDistanceKm === null} />
        <ContextItem icon="¥" label="预算" value={context.budgetMax === null ? '预算未设置' : `¥${formatNumber(context.budgetMax)} 以内`} unknown={context.budgetMax === null} />
      </div>
    </section>
  )
}

function ContextItem({
  icon,
  label,
  value,
  unknown,
}: {
  icon: string
  label: string
  value: string
  unknown: boolean
}) {
  return (
    <div className={`trip-context-item${unknown ? ' is-unknown' : ''}`}>
      <span className="trip-context-icon" aria-hidden="true">{icon}</span>
      <span><small>{label}</small><b>{value}</b></span>
    </div>
  )
}

function formatDuration(minutes: number | null): string {
  if (minutes === null) return '时间未设置'
  if (minutes >= 60 && minutes % 60 === 0) return `${minutes / 60} 小时`
  return `${minutes} 分钟`
}

function formatNumber(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(1)
}
