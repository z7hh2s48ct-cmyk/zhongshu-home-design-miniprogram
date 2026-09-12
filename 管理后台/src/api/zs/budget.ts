import request from '@/config/axios'

const BASE = '/design/v1/budget'
export type CatalogKind = 'regions' | 'items' | 'options' | 'prices'
export interface CatalogRow {
  regionId?: string
  itemId?: string
  optionId?: string
  priceId?: string
  code?: string
  name?: string
  label?: string
  category?: 'BODY' | 'EXTERIOR'
  source?: 'STANDARD' | 'CUSTOM_TEMPLATE'
  enabled?: boolean
  publicSelectable?: boolean
  sortOrder?: number
  selectionGroup?: string
  unit?: string
  quantitySource?: string
  quantityKey?: string | null
  sourceReference?: string | null
  regionCode?: string
  unitPriceCents?: number | null
  freeReason?: string | null
  status?: 'DRAFT' | 'PUBLISHED' | 'DISABLED'
  effectiveAt?: string | null
  expiresAt?: string | null
  version: number
}
export interface CatalogPage {
  list: CatalogRow[]
  total: number
}

export const getCatalogPage = (kind: CatalogKind, params: Record<string, unknown>) =>
  request.get<CatalogPage>({ url: `${BASE}/${kind}`, params })

export const saveCatalog = (
  kind: CatalogKind,
  id: string | undefined,
  data: Record<string, unknown>,
  key: string
) => {
  const options = {
    url: `${BASE}/${kind}${id ? `/${encodeURIComponent(id)}` : ''}`,
    data,
    headers: { 'Idempotency-Key': key }
  }
  return id ? request.patch<CatalogRow>(options) : request.post<CatalogRow>(options)
}

export const transitionPrice = (
  priceId: string,
  action: 'publish' | 'disable',
  data: { expectedVersion: number; reason: string },
  key: string
) =>
  request.post<CatalogRow>({
    url: `${BASE}/prices/${encodeURIComponent(priceId)}/${action}`,
    data,
    headers: { 'Idempotency-Key': key }
  })
