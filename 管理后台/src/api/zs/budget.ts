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

// T14：用户覆盖价（我的当地单价）只读审计视图；管理端不提供干预入口
export interface AccountPriceRow {
  accountId: string
  regionCode: string
  optionId: string
  optionLabel: string
  itemCode: string
  unitPriceCents: number
  reason: string | null
  status: 'ACTIVE' | 'REMOVED'
  updatedAt: string
}
export interface AccountPricePage {
  list: AccountPriceRow[]
  total: number
}

export const getAccountPrices = (params: {
  accountId: string
  regionCode: string
  pageNo: number
  pageSize: number
}) => request.get<AccountPricePage>({ url: `${BASE}/account-prices`, params })
// E-7 覆盖价账号搜索（复用账号分页端点）
export const searchAccountsApi = (params: any) =>
  request.get<any>({ url: '/design/v1/accounts', params })
export const getAccountById = (accountId: string) =>
  request.get<any>({
    url: `${BASE.replace('/budget', '')}/accounts/${encodeURIComponent(accountId)}`
  })
// F-4 代用户设置/清除覆盖价（design:budget:price-publish）
export const setAccountPriceForUser = (data: {
  accountId: string
  optionId: string
  regionCode: string
  unitPriceCents: number | null
  reason: string
}) => request.put<any>({ url: `${BASE}/account-prices`, data })
