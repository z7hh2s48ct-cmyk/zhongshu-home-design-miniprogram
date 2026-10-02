import request from '@/config/axios'

export interface GenerationPriceRule {
  ruleId: string
  stage: 'FLAT' | 'ELEVATION'
  resolution: '2K' | '4K'
  unitPointCost: number
  minCount: number
  maxCount: number
  effectiveAt: string
  expiresAt: string | null
  status: 'ACTIVE' | 'RETIRED'
  version: number
}

export type CreateGenerationPrice = Omit<GenerationPriceRule, 'ruleId' | 'status' | 'version'>
const base = '/design/v1/generation-price-rules'
export const getPriceRules = (params: {
  stage?: string
  resolution?: string
  status?: string
  pageNo: number
  pageSize: number
}): Promise<{ list: GenerationPriceRule[]; total: number }> => request.get({ url: base, params })
export const createPriceRule = (data: CreateGenerationPrice) => request.post({ url: base, data })
export const retirePriceRule = (ruleId: string, data?: { reason: string }) =>
  request.patch({ url: `${base}/${encodeURIComponent(ruleId)}/retire`, data })
// 删除已停用规则（逻辑删除；生效中须先停用，追溯走流水快照）
export const deletePriceRule = (ruleId: string) =>
  request.delete({ url: `${base}/${encodeURIComponent(ruleId)}` })
