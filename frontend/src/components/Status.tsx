/** 加载中。用于「准备题目」「计算结果」「载入画像」等场景。 */
export function Loading({ text }: { text: string }) {
  return (
    <div className="loading">
      <div className="spinner" />
      <div>{text}</div>
    </div>
  )
}

/**
 * 错误面板。
 *
 * 把原始错误信息用等宽字体展示，是因为它多半是后端返回的报文或者异常消息，
 * 用等宽字体保留格式更好读；用户要截图反馈时也方便。
 */
export function ErrorBox({
  title,
  message,
  onRetry,
}: {
  title: string
  message: string
  onRetry?: () => void
}) {
  return (
    <div className="error-box">
      <div style={{ fontSize: 17, fontWeight: 600, marginBottom: 6 }}>{title}</div>
      <div className="msg">{message}</div>
      {onRetry && (
        <button className="btn btn-primary" type="button" onClick={onRetry}>
          重试
        </button>
      )}
    </div>
  )
}
