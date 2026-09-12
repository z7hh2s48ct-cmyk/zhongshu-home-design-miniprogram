import request from '@/config/axios'
import type { BudgetLine } from './budget-revision'

const BASE = '/design/v1/budget'

export interface PublicQuoteItem {
  itemId: string | null
  itemCode: string
  category: 'BODY' | 'EXTERIOR'
  publicName: string
  source: BudgetLine['source']
  completeness: 'COMPLETE' | 'INCOMPLETE'
  pricedSubtotalCents: number
  amountCents: number | null
  lines: {
    lineId: string
    optionLabel: string | null
    status: BudgetLine['status']
    amountCents: number | null
  }[]
}
export interface PublicQuote {
  quoteId: string
  quoteVersion: number
  budgetId: string
  revisionId: string
  projectId: string
  projectName: string | null
  resultVersionId: string | null
  schemeName: string | null
  regionName: string | null
  status: 'DRAFT' | 'PUBLISHED' | 'WITHDRAWN'
  current: boolean
  calculatedTotalCents: number
  adjustmentCents: number
  finalPriceCents: number
  categoryTotals: Record<'BODY' | 'EXTERIOR', number>
  items: PublicQuoteItem[]
  publishedAt: string | null
  withdrawnAt: string | null
  disclaimer: string
}
export interface BudgetQuote {
  quoteId: string
  budgetId: string
  revisionId: string
  revisionNo: number
  quoteVersion: number
  status: PublicQuote['status']
  version: number
  calculatedTotalCents: number
  adjustmentCents: number
  finalPriceCents: number
  reason: string
  actorId: string
  stale: boolean
  currentPublic: boolean
  publishedBy: string | null
  publishedAt: string | null
  withdrawnBy: string | null
  withdrawnAt: string | null
  withdrawalReason: string | null
  preview: PublicQuote
}
export type QuoteCreateCommand = {
  revisionId: string
  expectedVersion: number
  reason: string
} & ({ finalPriceCents: number } | { adjustmentCents: number })

export const getQuotes = (budgetId: string) =>
  request.get<BudgetQuote[]>({ url: `${BASE}/estimates/${encodeURIComponent(budgetId)}/quotes` })
export const getQuote = (quoteId: string) =>
  request.get<BudgetQuote>({ url: `${BASE}/quotes/${encodeURIComponent(quoteId)}` })
export const createQuote = (budgetId: string, data: QuoteCreateCommand, key: string) =>
  request.post<BudgetQuote>({
    url: `${BASE}/estimates/${encodeURIComponent(budgetId)}/quotes`,
    data,
    headers: { 'Idempotency-Key': key }
  })
export const publishQuote = (
  quoteId: string,
  data: { expectedVersion: number; reason: string },
  key: string
) =>
  request.post<BudgetQuote>({
    url: `${BASE}/quotes/${encodeURIComponent(quoteId)}/publish`,
    data,
    headers: { 'Idempotency-Key': key }
  })
export const withdrawQuote = (
  quoteId: string,
  data: { expectedVersion: number; reason: string },
  key: string
) =>
  request.post<BudgetQuote>({
    url: `${BASE}/quotes/${encodeURIComponent(quoteId)}/withdraw`,
    data,
    headers: { 'Idempotency-Key': key }
  })
