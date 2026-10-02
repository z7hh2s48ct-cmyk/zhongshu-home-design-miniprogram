/**
 * 众墅之家管理端展示格式化。
 * 后端 Jackson 把 LocalDateTime 统一序列化为 epoch 毫秒，时间列必须经 fmtTime 转换。
 */

/** 毫秒时间戳 / ISO 字符串 → 「yyyy-MM-dd HH:mm」；空值返回 — */
export const fmtTime = (value: any): string => {
  if (value === null || value === undefined || value === '') return '—'
  let raw: any = value
  if (typeof value === 'string' && /^\d+$/.test(value.trim())) raw = Number(value.trim())
  const date = new Date(raw)
  if (isNaN(date.getTime())) return String(value)
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-GB', {
      timeZone: 'Asia/Shanghai',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23'
    })
      .formatToParts(date)
      .map((part) => [part.type, part.value])
  )
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`
}

/** 毫秒时间戳 / ISO 字符串 → 「yyyy-MM-dd」 */
export const fmtDate = (value: any): string => fmtTime(value).slice(0, 10)

// 兼容两代风格码：老表单（EURO/AMERICAN）与预设表（CHINESE/EUROPEAN 等）；
// 未映射的自定义风格直接展示原文
export const STYLE_TEXT: Record<string, string> = {
  NEW_CHINESE: '新中式',
  MODERN: '现代',
  CHINESE: '中式',
  EUROPEAN: '欧式',
  EURO: '欧式',
  AMERICAN: '美式',
  JAPANESE: '日式',
  FRENCH: '法式',
  COUNTRYSIDE: '田园'
}
export const styleText = (code: any): string => STYLE_TEXT[code] || code || '—'

export const AUDIT_EVENT_TEXT: Record<string, string> = {
  MANUAL_POINT_ADJUSTMENT: '人工调点',
  ACCESS_CODE_ISSUED: '授权码发放',
  ACCESS_CODE_DISABLED: '授权码停用',
  ACCESS_GRANT_REVOKED: '授权解绑',
  ACCESS_CODE_REDEEMED: '授权码兑换',
  SUBMISSION_REVIEWED: '投稿审核',
  SUBMISSION_PUBLISHED: '投稿发布',
  ORDER_REFUND: '订单退款',
  EXPORT_FILE: '数据导出'
}
export const auditEventText = (code: any): string => AUDIT_EVENT_TEXT[code] || code || '—'

export const AUDIT_RESULT_TEXT: Record<string, string> = {
  SUCCESS: '成功',
  FAILED: '失败',
  DENIED: '已拒绝'
}
export const auditResultText = (code: any): string => AUDIT_RESULT_TEXT[code] || code || '—'
