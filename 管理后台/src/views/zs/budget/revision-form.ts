import type {
  Addition,
  BudgetDetail,
  BudgetLine,
  Category,
  LinePatch,
  RevisionCommand
} from '@/api/zs/budget-revision'
import type { CatalogRow } from '@/api/zs/budget'
import { fmtTime } from '@/utils/zsFormat'
import { formatCents, parsePrice, quantityKeys, units } from './catalog-form'

export { formatCents, units }
const fieldNames: Record<string, string> = {
  regionCode: '建造地区',
  footprintArea: '占地面积',
  buildingArea: '建筑面积',
  roofArea: '屋顶面积',
  floorCount: '建筑层数',
  faceWidth: '面宽',
  depth: '进深',
  ...Object.fromEntries(quantityKeys.map((entry) => [entry.value, entry.label]))
}
const inputSourceNames: Record<string, string> = {
  PROJECT: '项目导入',
  DERIVED: '按已知参数推导',
  USER_OVERRIDE: '用户修改',
  ADMIN_OVERRIDE: '后台核定调整',
  FIXED_ONE: '明确固定一份',
  PROJECT_CUSTOM: '本项目临时项',
  REGIONAL_PRICE: '已发布地区价'
}
export const fieldLabel = (field: string) => fieldNames[field] || field
export const sourceLabel = (source?: string | null) =>
  source ? inputSourceNames[source] || source : '未记录'
export const warningLabel = (code: string) =>
  code === 'BUILDING_AREA_REVIEW_REQUIRED'
    ? '实际建筑面积与推导面积不一致，需要复核；原实际面积未被覆盖'
    : /^DERIVED_.+_OUT_OF_RANGE$/.test(code)
      ? fieldLabel(code.slice(8, -13)) + '推导结果超出允许范围，需要核对'
      : code
export const sourceNames = {
  STANDARD: '标准项',
  CUSTOM_TEMPLATE: '目录模板',
  PROJECT_CUSTOM: '项目临时项'
}
export const statusNames = {
  PRICED: '已计价',
  MISSING_PRICE: '待补价',
  MISSING_QUANTITY: '待补量',
  MISSING_BOTH: '待补量及价格',
  EXCLUDED: '已排除'
}
export const validId = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[1-9]\d{0,18}$/.test(value) &&
  (value.length < 19 || value <= '9223372036854775807')
export const errorText = (error: any) => error?.msg || error?.message || '请求失败，请重试'
// D2-1 时间统一：收敛到 fmtTime（上海时区，分钟精度），不再随浏览器时区漂移
export const displayTime = (value: string) => fmtTime(value)
const optional = (value: string, label: string) => {
  if (value.length > 500) throw Error(`${label}不能超过500字`)
  return value.trim() || null
}
export function required(value: string, label: string, max = 500) {
  if (!value.trim() || value.length > max) throw Error(`${label}不能为空且不能超过${max}字`)
  return value.trim()
}

export function parseQuantity(text: string, unit: string, fixedOne = false): string | null {
  if (text === '') return null
  if (!units.some((entry) => entry.value === unit) || !/^(0|[1-9]\d{0,6})(\.\d{1,4})?$/.test(text))
    throw Error('数量须为正数，最多四位小数，不接受科学计数法')
  const [whole, fraction = ''] = text.split('.')
  const scaled = BigInt(whole) * 10000n + BigInt(fraction.padEnd(4, '0'))
  const count = !['SQM', 'METER'].includes(unit)
  if (
    scaled <= 0n ||
    scaled > (count ? 100000n : 1000000n) * 10000n ||
    (count && scaled % 10000n !== 0n)
  )
    throw Error(count ? '数量须为不超过10万的正整数' : '数量须大于0且不超过100万')
  if (fixedOne && scaled !== 10000n) throw Error('固定一份的数量只能留空或填写1')
  const decimal = fraction.replace(/0+$/, '')
  return whole + (decimal ? '.' + decimal : '')
}

export interface WorkLine {
  key: string
  original: BudgetLine | null
  kind: 'EXISTING' | 'PROJECT_CUSTOM' | 'CATALOG_OPTION'
  itemCode: string
  publicName: string
  category: Category
  unit: string
  optionId: string
  optionLabel: string
  quantityRule: string
  quantityText: string
  priceYuan: string
  manualQuantity: boolean
  manualPrice: boolean
  freeReason: string
  excludedReason: string
  internalNote: string
  removed: boolean
}
export function workLine(line?: BudgetLine, key = ''): WorkLine {
  return {
    key: line?.lineId || key,
    original: line ? { ...line } : null,
    kind: line ? 'EXISTING' : 'PROJECT_CUSTOM',
    itemCode: line?.itemCode || '',
    publicName: line?.publicName || '',
    category: line?.category || 'EXTERIOR',
    unit: line?.unit || 'ITEM',
    optionId: line?.optionId || '',
    optionLabel: line?.optionLabel || '',
    quantityRule: line?.quantitySource || '',
    quantityText: line ? (line.quantity ?? '') : '1',
    priceYuan: line?.unitPriceCents == null ? '' : formatCents(line.unitPriceCents),
    manualQuantity: !!line,
    manualPrice: !!line,
    freeReason: line?.freeReason || '',
    excludedReason: line?.excludedReason || '',
    internalNote: line?.internalNote || '',
    removed: false
  }
}
export function templateLine(option: CatalogRow, item: CatalogRow, key: string): WorkLine {
  if (
    item.source !== 'CUSTOM_TEMPLATE' ||
    !item.enabled ||
    !option.enabled ||
    option.itemId !== item.itemId ||
    !validId(option.optionId)
  )
    throw Error('只能添加已启用目录模板的有效选项')
  return {
    ...workLine(undefined, key),
    kind: 'CATALOG_OPTION',
    itemCode: item.code!,
    publicName: item.name!,
    category: item.category!,
    unit: option.unit!,
    optionId: option.optionId,
    optionLabel: option.label || '',
    quantityRule: option.quantitySource || '',
    quantityText: '',
    manualQuantity: false,
    manualPrice: false
  }
}
export const fixedOne = (line: WorkLine) =>
  line.quantityRule === 'FIXED_ONE' ||
  ((line.kind === 'PROJECT_CUSTOM' || line.original?.source === 'PROJECT_CUSTOM') &&
    line.unit === 'ITEM')
export function eligibleOptions(line: BudgetLine, items: CatalogRow[], options: CatalogRow[]) {
  if (line.source !== 'STANDARD' || line.optionId) return []
  const item = items.find(
    (item) => item.itemId === line.itemId && item.source === 'STANDARD' && item.enabled
  )
  return !item
    ? []
    : options.filter(
        (option) =>
          option.enabled &&
          option.itemId === line.itemId &&
          (!line.selectionGroup ||
            line.selectionGroup === 'ITEM' ||
            option.selectionGroup === line.selectionGroup)
      )
}

/** Only changed fields are sent: omitted keeps the server value; explicit null clears it. */
function linePatch(line: WorkLine): LinePatch {
  const original = line.original
  const selecting = !!original && line.optionId !== (original.optionId || '')
  const catalogDefault = line.kind === 'CATALOG_OPTION' || selecting
  const patch: LinePatch = {}
  if (
    original?.source === 'STANDARD' &&
    !line.optionId &&
    (line.quantityText !== '' || line.priceYuan !== '')
  )
    throw Error('未选标准选项的占位行不能填写量价，请先补选；排除时也须保持量价为空')
  if (selecting) {
    if (original!.source !== 'STANDARD' || original!.optionId || !validId(line.optionId))
      throw Error('已有标准选项不能替换')
    patch.optionId = line.optionId
  }
  if (
    (!catalogDefault || line.manualQuantity) &&
    (!original || line.quantityText !== (original.quantity ?? '') || selecting)
  )
    patch.quantity = parseQuantity(line.quantityText, line.unit, fixedOne(line))
  if (
    (!catalogDefault || line.manualPrice) &&
    (!original ||
      line.priceYuan !==
        (original.unitPriceCents == null ? '' : formatCents(original.unitPriceCents)) ||
      selecting)
  )
    patch.unitPriceCents = parsePrice(line.priceYuan)
  for (const [field, label] of [
    ['freeReason', '免费原因'],
    ['excludedReason', '排除原因'],
    ['internalNote', '内部备注']
  ] as const) {
    const value = optional(line[field], label)
    if (original ? value !== original[field] : value != null) patch[field] = value
  }
  const effectivePrice = Object.hasOwn(patch, 'unitPriceCents')
    ? patch.unitPriceCents
    : catalogDefault
      ? undefined
      : original?.unitPriceCents
  if (effectivePrice === 0) required(line.freeReason, '免费原因')
  else if (line.freeReason.trim()) throw Error('仅明确免费（专用单价0元）可填写免费原因')
  if (Object.hasOwn(patch, 'unitPriceCents') && patch.unitPriceCents !== 0) patch.freeReason = null
  return patch
}
export function revisionPayload(
  detail: BudgetDetail,
  lines: WorkLine[],
  reason: string
): RevisionCommand {
  if (detail.readOnly || !Number.isSafeInteger(detail.currentVersion) || detail.currentVersion < 1)
    throw Error('历史修订不可编辑，请打开当前版本')
  const result: RevisionCommand = {
    expectedVersion: detail.currentVersion,
    reason: required(reason, '修订原因'),
    updates: [],
    additions: [],
    removeLineIds: []
  }
  const keys = new Set<string>()
  for (const line of lines) {
    if (keys.has(line.key)) throw Error('重复预算行')
    keys.add(line.key)
    if (line.removed) {
      if (line.original?.source === 'STANDARD') throw Error('标准项不可删除，请填写排除原因')
      if (line.original) result.removeLineIds.push(line.original.lineId)
      continue
    }
    const patch = linePatch(line)
    if (line.original) {
      if (!validId(line.original.lineId)) throw Error('预算行编号无效')
      if (Object.keys(patch).length) result.updates.push({ lineId: line.original.lineId, ...patch })
    } else {
      if (!/^[A-Za-z0-9_-]{1,64}$/.test(line.key)) throw Error('临时操作编号无效')
      let addition: Addition
      if (line.kind === 'CATALOG_OPTION') {
        if (!validId(line.optionId)) throw Error('请选择模板选项')
        addition = {
          kind: 'CATALOG_OPTION',
          clientKey: line.key,
          optionId: line.optionId,
          ...patch
        }
      } else {
        const itemCode = required(line.itemCode, '临时项编码', 64)
        if (!/^[A-Z0-9_]+$/.test(itemCode) || !['BODY', 'EXTERIOR'].includes(line.category))
          throw Error('临时项编码或分类无效')
        addition = {
          kind: 'PROJECT_CUSTOM',
          clientKey: line.key,
          itemCode,
          publicName: required(line.publicName, '临时项名称', 100),
          category: line.category,
          unit: line.unit,
          ...patch
        }
      }
      result.additions.push(addition)
    }
  }
  if (!result.updates.length && !result.additions.length && !result.removeLineIds.length)
    throw Error('请先修改预算明细')
  return result
}

export function preview(detail: BudgetDetail, lines: WorkLine[]) {
  const totals = { BODY: 0n, EXTERIOR: 0n }
  let complete =
    !detail.missingFields.length && !detail.warnings.length && lines.some((line) => !line.removed)
  const rows = lines
    .filter((line) => !line.removed)
    .map((line) => {
      if (
        line.original?.source === 'STANDARD' &&
        !line.optionId &&
        (line.quantityText !== '' || line.priceYuan !== '')
      )
        throw Error('未选标准选项的占位行不能填写量价')
      const selecting = !!line.original && line.optionId !== (line.original.optionId || '')
      const automatic = line.kind === 'CATALOG_OPTION' || selecting
      let amountCents: number | null = null
      let status = 'EXCLUDED'
      const quantity =
        automatic && !line.manualQuantity
          ? null
          : parseQuantity(line.quantityText, line.unit, fixedOne(line))
      const price = automatic && !line.manualPrice ? null : parsePrice(line.priceYuan)
      if (price === 0) required(line.freeReason, '免费原因')
      else if (line.freeReason.trim()) throw Error('仅明确免费（专用单价0元）可填写免费原因')
      if (!line.excludedReason.trim()) {
        const placeholder = line.original?.source === 'STANDARD' && !line.optionId
        status =
          placeholder || (quantity == null && price == null)
            ? 'MISSING_BOTH'
            : quantity == null
              ? 'MISSING_QUANTITY'
              : price == null
                ? 'MISSING_PRICE'
                : 'PRICED'
        if (status === 'PRICED') {
          const [whole, fraction = ''] = quantity!.split('.')
          const scaled = BigInt(whole) * 10000n + BigInt(fraction.padEnd(4, '0'))
          const cents = (scaled * BigInt(price!) + 5000n) / 10000n
          if (cents > 10000000000n) throw Error('单行金额超出上限')
          totals[line.category] += cents
          amountCents = Number(cents)
        } else complete = false
      }
      return { key: line.key, status, amountCents }
    })
  const subtotal = totals.BODY + totals.EXTERIOR
  if (subtotal > 10000000000n) throw Error('预算金额超出上限')
  return {
    rows,
    categoryTotals: { BODY: Number(totals.BODY), EXTERIOR: Number(totals.EXTERIOR) },
    pricedSubtotalCents: Number(subtotal),
    totalCents: complete ? Number(subtotal) : null
  }
}

export function differences(current: BudgetDetail, previous: BudgetDetail) {
  const before = new Map(previous.lines.map((line) => [line.lineKey, line]))
  const after = new Map(current.lines.map((line) => [line.lineKey, line]))
  return [...new Set([...before.keys(), ...after.keys()])].flatMap((key) => {
    const left = before.get(key),
      right = after.get(key)
    const description = (line?: BudgetLine) =>
      line
        ? `${line.quantity ?? '待补量'} × ${formatCents(line.unitPriceCents)}元；${statusNames[line.status]}；${line.optionLabel || '无选项'}；备注：${line.internalNote || '无'}；排除：${line.excludedReason || '无'}；免费：${line.freeReason || '无'}`
        : '不存在'
    const oldValue = description(left),
      newValue = description(right)
    return oldValue === newValue
      ? []
      : [
          {
            key,
            name: (right || left)!.publicName,
            source: sourceNames[(right || left)!.source],
            oldValue,
            newValue
          }
        ]
  })
}
export function templatePayload(
  detail: BudgetDetail,
  line: BudgetLine,
  code: string,
  name: string,
  reason: string
) {
  if (detail.readOnly || line.source !== 'PROJECT_CUSTOM')
    throw Error('仅当前修订中的项目临时项可另存模板')
  const stableCode = required(code, '模板编码', 64)
  if (!/^[A-Z0-9_]+$/.test(stableCode)) throw Error('模板编码只能包含大写字母、数字和下划线')
  return {
    expectedVersion: detail.currentVersion,
    code: stableCode,
    name: required(name, '模板名称', 100),
    reason: required(reason, '另存原因')
  }
}
