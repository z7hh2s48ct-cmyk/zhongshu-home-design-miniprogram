import request from '@/config/axios'

const BASE = '/design/v1/budget/estimates'
export type Category = 'BODY' | 'EXTERIOR'
export type LineSource = 'STANDARD' | 'CUSTOM_TEMPLATE' | 'PROJECT_CUSTOM'
export interface BudgetSummary {
  budgetId: string
  projectId: string
  userId: string
  resultVersionId: string | null
  projectName: string | null
  schemeName: string | null
  regionCode: string | null
  regionName: string | null
  model: 'ITEMIZED_V1'
  saved: boolean
  currentVersion: number
  revisionId: string
  revisionNo: number
  completeness: 'COMPLETE' | 'INCOMPLETE'
  pricedSubtotalCents: number
  totalCents: number | null
  createdAt: string
}
export interface BudgetLine {
  lineId: string
  lineKey: string
  itemId: string | null
  optionId: string | null
  priceVersionId: string | null
  itemCode: string
  publicName: string
  optionLabel: string | null
  category: Category
  source: LineSource
  selectionGroup: string | null
  unit: string
  quantity: string | null
  unitPriceCents: number | null
  amountCents: number | null
  status: 'PRICED' | 'MISSING_PRICE' | 'MISSING_QUANTITY' | 'MISSING_BOTH' | 'EXCLUDED'
  freeReason: string | null
  excludedReason: string | null
  internalNote: string | null
  originalQuantity: string | null
  originalUnitPriceCents: number | null
  quantitySource: string | null
  priceSource: string | null
  sourceReference: string | null
}
export interface BudgetDetail extends BudgetSummary {
  readOnly: boolean
  categoryTotals: Record<Category, number>
  inputSnapshot: {
    importedValues?: Record<string, unknown>
    importedSources?: Record<string, string>
    inputOverrides?: Record<string, unknown>
    currentValues?: Record<string, unknown>
    currentSources?: Record<string, string>
    quantities?: Record<string, unknown>
    requirementSnapshotIds?: string[]
  }
  missingFields: string[]
  warnings: string[]
  lines: BudgetLine[]
}
export interface RevisionRef {
  revisionId: string
  revisionNo: number
  actorType: string
  actorId: string | null
  changeReason: string | null
  completeness: 'COMPLETE' | 'INCOMPLETE'
  pricedSubtotalCents: number
  totalCents: number | null
  createdAt: string
}
export interface LinePatch {
  quantity?: string | null
  unitPriceCents?: number | null
  freeReason?: string | null
  excludedReason?: string | null
  internalNote?: string | null
  optionId?: string
}
export type Addition = LinePatch &
  (
    | {
        kind: 'PROJECT_CUSTOM'
        clientKey: string
        itemCode: string
        publicName: string
        category: Category
        unit: string
      }
    | { kind: 'CATALOG_OPTION'; clientKey: string; optionId: string }
  )
export interface RevisionCommand {
  expectedVersion: number
  reason: string
  updates: (LinePatch & { lineId: string })[]
  additions: Addition[]
  removeLineIds: string[]
}

export const getEstimates = (params: {
  projectId?: string
  completeness?: string
  pageNo: number
  pageSize: number
}) => request.get<{ list: BudgetSummary[]; total: number }>({ url: BASE, params })
export const getEstimate = (budgetId: string, revisionId?: string) =>
  request.get<BudgetDetail>({
    url: `${BASE}/${encodeURIComponent(budgetId)}`,
    params: revisionId ? { revisionId } : {}
  })
export const getRevisions = (budgetId: string) =>
  request.get<RevisionRef[]>({ url: `${BASE}/${encodeURIComponent(budgetId)}/revisions` })
export const createRevision = (budgetId: string, data: RevisionCommand, key: string) =>
  request.post<BudgetDetail>({
    url: `${BASE}/${encodeURIComponent(budgetId)}/revisions`,
    data,
    headers: { 'Idempotency-Key': key }
  })
export const saveLineTemplate = (
  budgetId: string,
  lineId: string,
  data: { expectedVersion: number; code: string; name: string; reason: string },
  key: string
) =>
  request.post<{
    itemId: string
    code: string
    name: string
    enabled: boolean
    publicSelectable: boolean
  }>({
    url: `${BASE}/${encodeURIComponent(budgetId)}/items/${encodeURIComponent(lineId)}/template`,
    data,
    headers: { 'Idempotency-Key': key }
  })
