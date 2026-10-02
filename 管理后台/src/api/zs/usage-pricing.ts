import request from '@/config/axios'

export type UsageProduct = 'BUDGET_ESTIMATE' | 'AI_PROMPT'
export interface UsagePriceRule {
  ruleId: string
  product: UsageProduct
  pointCost: number
  version: number
  effectiveAt: string
  expiresAt: string | null
  status: 'ACTIVE' | 'RETIRED'
}
const base = '/design/v1/usage-price-rules'
export const listRules = (params: {
  product?: string
  status?: string
  pageNo: number
  pageSize: number
}) => request.get({ url: base, params }) as Promise<{ list: UsagePriceRule[]; total: number }>
export const createRule = (data: {
  product: UsageProduct
  pointCost: number
  effectiveAt: string
  expiresAt: string | null
}) => request.post({ url: base, data })
export const retireRule = (id: string, data?: { reason: string }) =>
  request.patch({ url: `${base}/${encodeURIComponent(id)}/retire`, data })
