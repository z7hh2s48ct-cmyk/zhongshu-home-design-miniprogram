import type { BudgetDetail } from '@/api/zs/budget-revision'
import type { QuoteCreateCommand } from '@/api/zs/budget-quote'

export function yuanToCents(value: string, signed = false): number {
  const text = value.trim()
  const pattern = signed ? /^-?(?:0|[1-9]\d*)(?:\.\d{1,2})?$/ : /^(?:0|[1-9]\d*)(?:\.\d{1,2})?$/
  if (!pattern.test(text)) throw Error('金额必须是最多两位小数的元数值')
  const negative = text.startsWith('-')
  const [whole, decimal = ''] = (negative ? text.slice(1) : text).split('.')
  const cents = Number(whole) * 100 + Number(decimal.padEnd(2, '0'))
  if (!Number.isSafeInteger(cents) || cents > 10_000_000_000) throw Error('金额超出允许范围')
  return negative ? -cents : cents
}

export function quotePayload(
  detail: BudgetDetail,
  mode: 'FINAL' | 'ADJUSTMENT',
  amountYuan: string,
  reason: string
): QuoteCreateCommand {
  if (detail.readOnly || detail.revisionNo !== detail.currentVersion)
    throw Error('只能按当前预算修订创建报价')
  if (detail.completeness !== 'COMPLETE' || detail.totalCents == null)
    throw Error('预算仍有待补项，不能创建完整报价')
  const why = reason.trim()
  if (!why || why !== reason || why.length > 500)
    throw Error('请填写500字以内且首尾无空格的调价原因')
  const amount = yuanToCents(amountYuan, mode === 'ADJUSTMENT')
  const final = mode === 'FINAL' ? amount : detail.totalCents + amount
  if (!Number.isSafeInteger(final) || final < 0 || final > 10_000_000_000)
    throw Error('最终报价超出允许范围')
  return {
    revisionId: detail.revisionId,
    expectedVersion: detail.currentVersion,
    reason,
    ...(mode === 'FINAL' ? { finalPriceCents: amount } : { adjustmentCents: amount })
  }
}
