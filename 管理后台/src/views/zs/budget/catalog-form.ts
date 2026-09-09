import type { CatalogKind, CatalogRow } from '@/api/zs/budget'

export const quantitySources = [
  { value: 'FOOTPRINT_AREA', label: '占地面积', unit: 'SQM' },
  { value: 'BUILDING_AREA', label: '建筑面积', unit: 'SQM' },
  { value: 'ROOF_AREA', label: '屋顶面积', unit: 'SQM' },
  { value: 'WINDOW_AREA', label: '窗面积（已知值优先）', unit: 'SQM' },
  { value: 'PROJECT_QUANTITY', label: '项目核定工程量', unit: '' },
  { value: 'FIXED_ONE', label: '明确固定一份', unit: '' }
]
export const quantityKeys = [
  { value: 'DOOR_HOUSEHOLDS', label: '门户数', unit: 'HOUSEHOLD' },
  { value: 'WINDOW_AREA', label: '窗面积', unit: 'SQM' },
  { value: 'WALL_PAINT_AREA', label: '外墙核定施工面积', unit: 'SQM' },
  { value: 'CULTURE_STONE_LENGTH', label: '文化石施工长度', unit: 'METER' },
  { value: 'LIGHTING_WASHER_LENGTH', label: '洗墙灯长度', unit: 'METER' },
  { value: 'LIGHTING_STRIP_LENGTH', label: '灯带长度', unit: 'METER' },
  { value: 'LIGHTING_WALL_LAMP_COUNT', label: '壁灯数量', unit: 'PIECE' },
  { value: 'WATERPROOF_AREA', label: '天沟及露台防水面积', unit: 'SQM' },
  { value: 'INSULATED_WATERPROOF_AREA', label: '平顶隔热防水面积', unit: 'SQM' }
]
export const units = [
  { value: 'SQM', label: '平方米' },
  { value: 'METER', label: '米' },
  { value: 'PIECE', label: '个' },
  { value: 'SET', label: '套' },
  { value: 'HOUSEHOLD', label: '户' },
  { value: 'ITEM', label: '项' }
]

export const rowId = (kind: CatalogKind, row: CatalogRow) =>
  row[
    ({ regions: 'regionId', items: 'itemId', options: 'optionId', prices: 'priceId' } as const)[
      kind
    ]
  ]

export function formatCents(value: number | null | undefined): string {
  if (value == null) return '待补价'
  if (!Number.isSafeInteger(value) || value < 0) return '金额异常'
  return `${Math.floor(value / 100)}.${String(value % 100).padStart(2, '0')}`
}

/** Parse yuan text by digits, never binary floating point multiplication. Empty is missing, not free. */
export function parsePrice(text: string): number | null {
  if (text === '') return null
  if (!/^(0|[1-9]\d{0,6})(\.\d{1,2})?$/.test(text))
    throw new Error('单价须为非负金额，最多两位小数')
  const [whole, fraction = ''] = text.split('.')
  const cents = Number(whole) * 100 + Number(fraction.padEnd(2, '0'))
  if (cents > 100000000) throw new Error('单价不能超过100万元')
  return cents
}

export interface CatalogForm {
  code: string
  name: string
  category: 'BODY' | 'EXTERIOR'
  enabled: boolean
  publicSelectable: boolean
  sortOrder: number
  itemId: string
  label: string
  selectionGroup: string
  unit: string
  quantitySource: string
  quantityKey: string
  sourceReference: string
  regionCode: string
  optionId: string
  priceYuan: string
  freeReason: string
  effectiveAt: string
  expiresAt: string
}

export function createForm(row?: CatalogRow): CatalogForm {
  return {
    code: row?.code || '',
    name: row?.name || '',
    category: row?.category || 'EXTERIOR',
    enabled: row?.enabled || false,
    publicSelectable: row?.publicSelectable || false,
    sortOrder: row?.sortOrder ?? 0,
    itemId: row?.itemId || '',
    label: row?.label || '',
    selectionGroup: row?.selectionGroup || '',
    unit: row?.unit || 'SQM',
    quantitySource: row?.quantitySource || 'BUILDING_AREA',
    quantityKey: row?.quantityKey || '',
    sourceReference: row?.sourceReference || '',
    regionCode: row?.regionCode || '',
    optionId: row?.optionId || '',
    priceYuan: row?.unitPriceCents == null ? '' : formatCents(row.unitPriceCents),
    freeReason: row?.freeReason || '',
    effectiveAt: row?.effectiveAt || '',
    expiresAt: row?.expiresAt || ''
  }
}

function required(text: string, label: string, max = 100) {
  if (!text.trim() || text.length > max) throw new Error(`${label}不能为空且不能超过${max}字`)
  return text.trim()
}

export function formPayload(
  kind: CatalogKind,
  form: CatalogForm,
  editing?: CatalogRow
): Record<string, unknown> {
  const version = editing ? { expectedVersion: editing.version } : {}
  const code = () => {
    const value = required(form.code, '编码', kind === 'regions' ? 32 : 64)
    if (!(kind === 'regions' ? /^[A-Za-z0-9_-]+$/ : /^[A-Z0-9_]+$/).test(value))
      throw new Error('编码格式不正确')
    return value
  }
  if (kind === 'regions')
    return {
      ...(!editing ? { code: code() } : {}),
      name: required(form.name, '地区名称'),
      enabled: form.enabled,
      ...version
    }
  if (kind === 'items')
    return {
      ...(!editing ? { code: code(), category: form.category } : {}),
      name: required(form.name, '预算项名称'),
      enabled: form.enabled,
      publicSelectable: form.publicSelectable,
      sortOrder: form.sortOrder,
      ...version
    }
  if (kind === 'options') {
    if (!editing && !/^[1-9]\d{0,18}$/.test(form.itemId)) throw new Error('请选择所属预算项')
    if (!/^[A-Z0-9_]{1,64}$/.test(form.selectionGroup))
      throw new Error('选择组须为大写字母、数字或下划线')
    const source = quantitySources.find((s) => s.value === form.quantitySource)
    const quantityKey = form.quantitySource === 'PROJECT_QUANTITY' ? form.quantityKey : null
    const expectedUnit = source?.unit || quantityKeys.find((k) => k.value === quantityKey)?.unit
    if (
      !source ||
      !units.some((u) => u.value === form.unit) ||
      (expectedUnit && expectedUnit !== form.unit) ||
      (form.quantitySource === 'PROJECT_QUANTITY' && !expectedUnit) ||
      (form.quantitySource === 'FIXED_ONE' && !['ITEM', 'SET'].includes(form.unit))
    )
      throw new Error('数量来源与计价单位不匹配')
    if (form.sourceReference.length > 500) throw new Error('计价依据不能超过500字')
    return {
      ...(!editing ? { itemId: form.itemId, code: code() } : {}),
      label: required(form.label, '选项名称'),
      selectionGroup: form.selectionGroup,
      unit: form.unit,
      quantitySource: form.quantitySource,
      quantityKey,
      sourceReference: form.sourceReference || null,
      enabled: form.enabled,
      sortOrder: form.sortOrder,
      ...version
    }
  }
  if (!editing && (!form.regionCode || !/^[1-9]\d{0,18}$/.test(form.optionId)))
    throw new Error('请选择地区和计价选项')
  const cents = parsePrice(form.priceYuan)
  if (cents === 0) required(form.freeReason, '免费原因', 500)
  if (form.freeReason.length > 500) throw new Error('免费原因不能超过500字')
  const date = (value: string) => {
    if (!value) return null
    if (!Number.isFinite(Date.parse(value))) throw new Error('生效时间格式不正确')
    return new Date(value).toISOString()
  }
  const effectiveAt = date(form.effectiveAt),
    expiresAt = date(form.expiresAt)
  if (expiresAt && (!effectiveAt || expiresAt <= effectiveAt))
    throw new Error('失效时间须晚于生效时间')
  return {
    ...(!editing ? { regionCode: form.regionCode, optionId: form.optionId } : {}),
    unitPriceCents: cents,
    freeReason: cents === 0 ? form.freeReason.trim() : null,
    effectiveAt,
    expiresAt,
    ...version
  }
}
