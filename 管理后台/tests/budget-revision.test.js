const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript, compileTemplate } = require('vue/compiler-sfc')
const root = path.resolve(__dirname, '..')
const plain = (value) => JSON.parse(JSON.stringify(value))
const flush = () => new Promise((resolve) => setImmediate(resolve))
const budgetId = '9007199254740993',
  projectId = '9007199254740995',
  revisionId = '9007199254740997'
const lineId = '9007199254741001',
  optionId = '9007199254741003',
  itemId = '9007199254741005'

function evaluate(source, filename, mocks = {}, globals = {}) {
  const code = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  vm.runInNewContext(
    code,
    {
      module,
      exports: module.exports,
      ...globals,
      require(specifier) {
        return Object.hasOwn(mocks, specifier) ? mocks[specifier] : require(specifier)
      }
    },
    { filename }
  )
  return module.exports
}
const catalogPath = path.join(root, 'src/views/zs/budget/catalog-form.ts')
const catalog = evaluate(fs.readFileSync(catalogPath, 'utf8'), catalogPath)
const formPath = path.join(root, 'src/views/zs/budget/revision-form.ts')
const form = evaluate(fs.readFileSync(formPath, 'utf8'), formPath, { './catalog-form': catalog })
function line(changes = {}) {
  return {
    lineId,
    lineKey: 'CUSTOM_LOCAL_1',
    itemId: null,
    optionId: null,
    priceVersionId: null,
    itemCode: 'DRAIN',
    publicName: '排水沟',
    optionLabel: null,
    category: 'EXTERIOR',
    source: 'PROJECT_CUSTOM',
    selectionGroup: null,
    unit: 'METER',
    quantity: '2.5',
    unitPriceCents: 1001,
    amountCents: 2503,
    status: 'PRICED',
    freeReason: null,
    excludedReason: null,
    internalNote: null,
    originalQuantity: '2.5',
    originalUnitPriceCents: 1001,
    quantitySource: 'ADMIN_OVERRIDE',
    priceSource: 'ADMIN_OVERRIDE',
    sourceReference: null,
    ...changes
  }
}
function detail(changes = {}) {
  return {
    budgetId,
    projectId,
    userId: '9007199254741011',
    resultVersionId: '9007199254741013',
    projectName: '测试项目',
    schemeName: '原方案',
    regionCode: 'QZ',
    regionName: '泉州',
    model: 'ITEMIZED_V1',
    saved: true,
    currentVersion: 2,
    revisionId,
    revisionNo: 2,
    completeness: 'COMPLETE',
    pricedSubtotalCents: 2503,
    totalCents: 2503,
    createdAt: '2026-09-08T00:00:00Z',
    readOnly: false,
    categoryTotals: { BODY: 0, EXTERIOR: 2503 },
    inputSnapshot: {
      importedValues: { footprintArea: '100', roofArea: null },
      currentValues: { footprintArea: '120', roofArea: null },
      importedSources: { footprintArea: 'PROJECT' },
      currentSources: { footprintArea: 'USER_OVERRIDE' },
      quantities: { WINDOW_AREA: '80' },
      requirementSnapshotIds: ['9007199254741021']
    },
    missingFields: [],
    warnings: [],
    lines: [line()],
    ...changes
  }
}
const item = (changes = {}) => ({
  itemId,
  source: 'CUSTOM_TEMPLATE',
  code: 'DRAIN',
  name: '排水沟',
  category: 'EXTERIOR',
  enabled: true,
  publicSelectable: false,
  version: 1,
  ...changes
})
const option = (changes = {}) => ({
  itemId,
  optionId,
  code: 'DEFAULT',
  label: '标准施工',
  quantitySource: 'PROJECT_QUANTITY',
  unit: 'METER',
  selectionGroup: 'DEFAULT',
  enabled: true,
  version: 1,
  ...changes
})

/** Execute real SFC setup/reactivity and native Vue template compiler; not browser pixel acceptance. */
function component(name = 'detail', changes = {}) {
  const calls = [],
    notices = [],
    cleanups = [],
    guards = []
  const permissions = vue.reactive(
    new Set(
      changes.permissions || [
        'design:budget:query',
        'design:budget:edit',
        'design:budget:configure'
      ]
    )
  )
  const user = vue.reactive({ id: 1 })
  let tenant = '1',
    confirmAllowed = true
  const route = vue.reactive({ params: { budgetId }, query: changes.query || {} })
  const router = {
    push: async (value) => calls.push(['push', plain(value)]),
    replace: async (value) => {
      calls.push(['replace', plain(value)])
      route.query = value.query || {}
    }
  }
  const api = {
    getEstimates: async (params) => {
      calls.push(['list', plain(params)])
      return { list: [detail()], total: 1 }
    },
    getEstimate: async (id, rev) => {
      calls.push(['get', id, rev])
      return detail({ budgetId: id, ...(rev ? { revisionId: rev, readOnly: true } : {}) })
    },
    getRevisions: async (id) => {
      calls.push(['history', id])
      return [
        {
          revisionId,
          revisionNo: 2,
          actorType: 'ADMIN',
          actorId: '1',
          changeReason: '补量',
          completeness: 'COMPLETE',
          pricedSubtotalCents: 2503,
          totalCents: 2503,
          createdAt: '2026-09-08T00:00:00Z'
        }
      ]
    },
    createRevision: async (id, payload, key) => {
      calls.push(['save', id, plain(payload), key])
      return detail({ revisionId: '9007199254741099', revisionNo: 3, currentVersion: 3 })
    },
    saveLineTemplate: async (id, rowId, payload, key) => {
      calls.push(['template', id, rowId, plain(payload), key])
      return {
        itemId: '9007199254741101',
        code: payload.code,
        name: payload.name,
        enabled: false,
        publicSelectable: false
      }
    },
    ...changes.api
  }
  const catalogApi = {
    getCatalogPage: async (kind) => ({ list: kind === 'items' ? [item()] : [option()], total: 1 }),
    ...changes.catalogApi
  }
  const filename = path.join(root, 'src/views/zs/budget/' + name + '.vue')
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const script = compileScript(descriptor, { id: 'budget-revision-test' })
  const template = compileTemplate({
    source: descriptor.template.content,
    filename,
    id: 'budget-revision-test',
    compilerOptions: { bindingMetadata: script.bindings }
  })
  assert.deepEqual(template.errors, [])
  const compiled = evaluate(
    script.content,
    filename,
    {
      '@/api/zs/budget-revision': api,
      '@/api/zs/budget': catalogApi,
      './revision-form': form,
      '@/utils/permission': {
        checkPermi: (needed) => needed.some((value) => permissions.has(value))
      },
      '@/store/modules/user': { useUserStore: () => ({ getUser: user }) },
      '@/utils/auth': { getTenantId: () => tenant, getVisitTenantId: () => undefined },
      'vue-router': {
        onBeforeRouteLeave: (fn) => guards.push(fn),
        onBeforeRouteUpdate: (fn) => guards.push(fn)
      }
    },
    {
      ...vue,
      onMounted() {},
      onBeforeUnmount: (fn) => cleanups.push(fn),
      useRoute: () => route,
      useRouter: () => router,
      useMessage: () => ({
        success: (value) => notices.push(value),
        confirm: async (...args) => {
          calls.push(['confirm', ...args])
          if (!confirmAllowed) throw Error('cancel')
        }
      }),
      crypto: require('node:crypto').webcrypto
    }
  )
  const scope = vue.effectScope()
  const state = scope.run(() => compiled.default.setup({}, { expose() {} }))
  return {
    state,
    api,
    calls,
    notices,
    permissions,
    user,
    route,
    guards,
    setTenant(value) {
      tenant = value
    },
    cancelConfirm() {
      confirmAllowed = false
    },
    stop() {
      cleanups.forEach((fn) => fn())
      scope.stop()
    }
  }
}

test('修订数量按单位与四位小数校验，固定一份仅null或1，不猜测零和科学计数', () => {
  assert.equal(form.parseQuantity('', 'SQM'), null)
  assert.equal(form.parseQuantity('1.0000', 'ITEM', true), '1')
  assert.equal(form.parseQuantity('1000000.0000', 'METER'), '1000000')
  assert.equal(form.parseQuantity('0.0001', 'SQM'), '0.0001')
  for (const value of ['0', '-1', '1e3', '1.00001', '1000000.0001', ' 1'])
    assert.throws(() => form.parseQuantity(value, 'SQM'))
  for (const value of ['0.5', '100001']) assert.throws(() => form.parseQuantity(value, 'PIECE'))
  assert.throws(() => form.parseQuantity('2', 'SET', true), /固定一份/)
  assert.equal(form.validId('9007199254740993'), true)
  assert.equal(form.validId(9007199254740993), false)
  assert.equal(form.validId('9223372036854775808'), false)
  assert.equal(form.fieldLabel('footprintArea'), '占地面积')
  assert.equal(form.sourceLabel('USER_OVERRIDE'), '用户修改')
  assert.match(form.warningLabel('BUILDING_AREA_REVIEW_REQUIRED'), /实际建筑面积与推导面积不一致/)
  assert.match(form.warningLabel('DERIVED_buildingArea_OUT_OF_RANGE'), /建筑面积/)
  assert.equal(form.warningLabel('UNKNOWN_WARNING'), 'UNKNOWN_WARNING')
})

test('修订白名单只提交变化字段，显式null清空，零价需要原因，不改输入快照', () => {
  const original = detail(),
    draft = form.workLine(original.lines[0])
  draft.quantityText = ''
  draft.priceYuan = '0'
  draft.freeReason = '明确免收'
  draft.internalNote = '  人工核定  '
  const payload = form.revisionPayload(original, [draft], '补量补价')
  assert.deepEqual(plain(payload), {
    expectedVersion: 2,
    reason: '补量补价',
    updates: [
      {
        lineId,
        quantity: null,
        unitPriceCents: 0,
        freeReason: '明确免收',
        internalNote: '人工核定'
      }
    ],
    additions: [],
    removeLineIds: []
  })
  assert.equal(payload.inputSnapshot, undefined)
  assert.equal(original.lines[0].quantity, '2.5')
  draft.freeReason = ''
  assert.throws(() => form.revisionPayload(original, [draft], '修改'), /免费原因/)
  assert.throws(
    () => form.revisionPayload(original, [form.workLine(original.lines[0])], '无变化'),
    /先修改/
  )
  assert.throws(
    () => form.revisionPayload({ ...original, readOnly: true }, [draft], '修改'),
    /历史/
  )
})

test('标准ITEM占位可补同item选项，门窗选择组严格匹配；不发null覆盖自动量价', () => {
  const placeholder = line({
    source: 'STANDARD',
    itemId,
    itemCode: 'FOUNDATION',
    selectionGroup: 'ITEM',
    unit: 'SQM',
    quantity: null,
    unitPriceCents: null,
    optionId: null
  })
  const choices = [
    option({ selectionGroup: 'FOUNDATION', unit: 'SQM' }),
    option({ optionId: '123', itemId: '124', selectionGroup: 'FOUNDATION' })
  ]
  assert.equal(form.eligibleOptions(placeholder, [item({ source: 'STANDARD' })], choices).length, 1)
  assert.equal(
    form.eligibleOptions(
      { ...placeholder, selectionGroup: 'DOOR' },
      [item({ source: 'STANDARD' })],
      choices
    ).length,
    0
  )
  const draft = form.workLine(placeholder)
  draft.optionId = optionId
  draft.manualQuantity = false
  draft.manualPrice = false
  const payload = form.revisionPayload(detail({ lines: [placeholder] }), [draft], '补选')
  assert.deepEqual(plain(payload.updates), [{ lineId, optionId }])
  draft.manualQuantity = true
  draft.manualPrice = true
  const cleared = form.revisionPayload(detail({ lines: [placeholder] }), [draft], '明确待补')
  assert.equal(cleared.updates[0].quantity, null)
  assert.equal(cleared.updates[0].unitPriceCents, null)
})

test('标准行不能移除或换已选选项，占位量价不可绕过；固定临时ITEM不能填2', () => {
  const original = line({ source: 'STANDARD', itemId, optionId, itemCode: 'FOUNDATION' })
  let draft = form.workLine(original)
  draft.removed = true
  assert.throws(() => form.revisionPayload(detail(), [draft], '删除'), /标准项不可删除/)
  draft.removed = false
  draft.optionId = '123'
  assert.throws(() => form.revisionPayload(detail(), [draft], '替换'), /已有标准选项不能替换/)
  draft = form.workLine({ ...original, optionId: null, quantity: null, unitPriceCents: null })
  draft.quantityText = '1'
  draft.priceYuan = '1'
  draft.excludedReason = '排除'
  assert.throws(() => form.revisionPayload(detail(), [draft], '排除'), /占位/)
  assert.throws(() => form.preview(detail(), [draft]), /占位/)
  const custom = form.workLine(undefined, 'new-1')
  custom.itemCode = 'EXTRA'
  custom.publicName = '补项'
  custom.quantityText = '2'
  assert.throws(() => form.revisionPayload(detail(), [custom], '新增'), /固定一份/)
})

test('模板支持私有启用目录选项，自动量价省略、主动待补发null，禁用或标准模板拒绝', () => {
  const draft = form.templateLine(option(), item(), 'template-1')
  const payload = form.revisionPayload(detail(), [form.workLine(line()), draft], '补项')
  assert.deepEqual(plain(payload.additions), [
    { kind: 'CATALOG_OPTION', clientKey: 'template-1', optionId }
  ])
  draft.manualQuantity = true
  draft.manualPrice = true
  const cleared = form.revisionPayload(detail(), [draft], '待补').additions[0]
  assert.equal(cleared.quantity, null)
  assert.equal(cleared.unitPriceCents, null)
  assert.throws(() => form.templateLine(option(), item({ enabled: false }), 'x'))
  assert.throws(() => form.templateLine(option(), item({ source: 'STANDARD' }), 'x'))
  assert.match(form.sourceNames.CUSTOM_TEMPLATE, /目录/)
})

test('实时预览按四位数量逐行HALF_UP到分，分类汇总随增删排除变化且全局警告保留', () => {
  const a = form.workLine(line({ quantity: '0.5', unitPriceCents: 1, category: 'BODY' }))
  const b = form.workLine(line({ lineId: '102', lineKey: 'B', quantity: '0.5', unitPriceCents: 1 }))
  let result = form.preview(detail(), [a, b])
  assert.deepEqual(plain(result.categoryTotals), { BODY: 1, EXTERIOR: 1 })
  assert.equal(result.totalCents, 2)
  b.excludedReason = '本次不包含'
  result = form.preview(detail(), [a, b])
  assert.equal(result.pricedSubtotalCents, 1)
  a.removed = true
  result = form.preview(detail(), [a, b])
  assert.equal(result.pricedSubtotalCents, 0)
  a.removed = false
  result = form.preview(detail({ warnings: ['实际建筑面积待核定'] }), [a, b])
  assert.equal(result.totalCents, null)
  result = form.preview(detail({ missingFields: ['roofArea'] }), [a, b])
  assert.equal(result.totalCents, null)
  a.quantityText = '1000000'
  a.priceYuan = '1000000'
  assert.throws(() => form.preview(detail(), [a]), /金额超出/)
})

test('预览未知模板量价不伪造完整总额，免费缺原因包括排除行仍阻止保存', () => {
  const template = form.templateLine(option(), item(), 'template-1')
  assert.equal(form.preview(detail(), [template]).totalCents, null)
  const free = form.workLine(line({ unitPriceCents: 0, freeReason: null }))
  assert.throws(() => form.preview(detail(), [free]), /免费原因/)
  free.excludedReason = '已排除'
  assert.throws(() => form.preview(detail(), [free]), /免费原因/)
  free.freeReason = '免费'
  assert.equal(form.preview(detail(), [free]).pricedSubtotalCents, 0)
})

test('另存模板仅当前临时项，payload不复制数量单价或内部备注；差异按稳定lineKey而非名称合并', () => {
  const record = detail()
  assert.deepEqual(
    plain(form.templatePayload(record, record.lines[0], 'DRAIN_TEMPLATE', '排水模板', '可复用')),
    { expectedVersion: 2, code: 'DRAIN_TEMPLATE', name: '排水模板', reason: '可复用' }
  )
  assert.throws(() =>
    form.templatePayload({ ...record, readOnly: true }, record.lines[0], 'X', 'X', 'X')
  )
  assert.throws(() => form.templatePayload(record, line({ source: 'STANDARD' }), 'X', 'X', 'X'))
  const changed = detail({
    lines: [
      line({ lineId: '999', quantity: '3' }),
      line({ lineId: '998', lineKey: 'SECOND', quantity: '1' })
    ]
  })
  const differences = form.differences(changed, record)
  assert.equal(differences.length, 2)
  assert.equal(differences[0].key, 'CUSTOM_LOCAL_1')
})

test('新API保留大整数ID、分页/修订参数与幂等头，没有预算或目录隐式发布', async () => {
  const calls = []
  const http = Object.fromEntries(
    ['get', 'post'].map((method) => [
      method,
      async (options) => {
        calls.push([method, options])
        return {}
      }
    ])
  )
  const filename = path.join(root, 'src/api/zs/budget-revision.ts')
  const api = evaluate(fs.readFileSync(filename, 'utf8'), filename, {
    '@/config/axios': { __esModule: true, default: http }
  })
  await api.getEstimates({ projectId, completeness: 'INCOMPLETE', pageNo: 2, pageSize: 20 })
  await api.getEstimate(budgetId, revisionId)
  await api.getRevisions(budgetId)
  await api.createRevision(budgetId, { expectedVersion: 2, reason: '补项' }, 'same-key')
  await api.saveLineTemplate(
    budgetId,
    lineId,
    { expectedVersion: 2, code: 'X', name: 'X', reason: 'X' },
    'template-key'
  )
  assert.equal(calls[0][1].params.projectId, projectId)
  assert.equal(calls[1][1].params.revisionId, revisionId)
  assert.equal(calls[3][1].url, '/design/v1/budget/estimates/' + budgetId + '/revisions')
  assert.equal(calls[3][1].headers['Idempotency-Key'], 'same-key')
  assert.equal(
    calls[4][1].url,
    '/design/v1/budget/estimates/' + budgetId + '/items/' + lineId + '/template'
  )
  assert.equal(
    calls.some((call) => call[1].url.includes('publish')),
    false
  )
})

test('预算列表真实组件执行查询/分页/大整数导航，无权限或非法编号不请求，查询失败显示错误', async () => {
  const env = component('estimates')
  try {
    env.state.projectId.value = projectId
    env.state.pageNo.value = 2
    env.state.completeness.value = 'INCOMPLETE'
    await env.state.load()
    assert.deepEqual(env.calls[0][1], {
      projectId,
      completeness: 'INCOMPLETE',
      pageNo: 2,
      pageSize: 20
    })
    assert.equal(env.state.rows.value[0].model, 'ITEMIZED_V1')
    env.state.open(env.state.rows.value[0])
    assert.equal(env.calls.at(-1)[1], '/zs/budget-estimates/' + budgetId)
    env.state.projectId.value = 'bad'
    await env.state.load()
    assert.match(env.state.loadError.value, /完整项目编号/)
    env.state.projectId.value = ''
    env.api.getEstimates = async () => {
      throw Error('offline')
    }
    await env.state.load()
    assert.equal(env.state.loadError.value, 'offline')
  } finally {
    env.stop()
  }
  const denied = component('estimates', { permissions: [] })
  try {
    await denied.state.load()
    assert.equal(denied.calls.length, 0)
  } finally {
    denied.stop()
  }
})

test('详情真实组件历史/无编辑权限只读，无查询权限不读不写；原值null不显示0', async () => {
  for (const changes of [
    { permissions: [] },
    { permissions: ['design:budget:query'] },
    { query: { revisionId } }
  ]) {
    const env = component('detail', changes)
    try {
      await env.state.load()
      await flush()
      env.state.openCustom()
      await env.state.save()
      assert.equal(env.state.editorVisible.value, false)
      assert.equal(
        env.calls.some((call) => call[0] === 'save'),
        false
      )
      if (!changes.permissions?.length && changes.permissions) assert.equal(env.calls.length, 0)
    } finally {
      env.stop()
    }
  }
  const env = component()
  try {
    await env.state.load()
    assert.equal(env.state.inputRows.value.find((row) => row.field === '屋顶面积').current, '待补')
    assert.equal(
      env.state.inputRows.value.find((row) => row.field === '占地面积').source,
      '用户修改'
    )
  } finally {
    env.stop()
  }
})

test('详情编辑量价实时预览，提交保存防连点、失败保留副本和同体幂等键，改体换键', async () => {
  let rejectRequest
  const attempts = []
  const env = component('detail', {
    api: {
      createRevision: (id, body, key) => {
        attempts.push({ id, body: plain(body), key })
        return new Promise((resolve, reject) => {
          rejectRequest = reject
        })
      }
    }
  })
  try {
    const state = env.state
    await state.load()
    await flush()
    state.openLine(state.lines.value[0])
    state.editing.value.quantityText = '3'
    state.applyLine()
    assert.equal(state.previewResult.value.pricedSubtotalCents, 3003)
    state.reason.value = '核定施工长度'
    const pending = state.save()
    await state.save()
    assert.equal(attempts.length, 1)
    rejectRequest('error')
    await pending
    assert.match(state.saveError.value, /版本冲突请刷新后重新核对/)
    assert.equal(state.lines.value[0].quantityText, '3')
    assert.equal(env.notices.length, 0)
    const retry = state.save()
    assert.equal(attempts[0].key, attempts[1].key)
    rejectRequest(Error('timeout'))
    await retry
    state.lines.value[0].quantityText = '4'
    const changed = state.save()
    assert.notEqual(attempts[1].key, attempts[2].key)
    rejectRequest(Error('timeout'))
    await changed
    assert.deepEqual(attempts[0].body.updates, [{ lineId, quantity: '3' }])
    assert.equal(attempts[0].body.expectedVersion, 2)
  } finally {
    env.stop()
  }
})

test('占位标准项真实编辑补选只发optionId，冻结FIXED_ONE不被今日目录覆盖；模板重复选项禁止', async () => {
  const placeholder = line({
    source: 'STANDARD',
    itemId,
    itemCode: 'FOUNDATION',
    selectionGroup: 'ITEM',
    optionId: null,
    quantity: null,
    unitPriceCents: null,
    unit: 'SQM'
  })
  const env = component('detail', {
    api: { getEstimate: async () => detail({ lines: [placeholder] }) },
    catalogApi: {
      getCatalogPage: async (kind) => ({
        list:
          kind === 'items'
            ? [item({ source: 'STANDARD' })]
            : [option({ selectionGroup: 'FOUNDATION', unit: 'SQM' })],
        total: 1
      })
    }
  })
  try {
    const state = env.state
    await state.load()
    await flush()
    state.openLine(state.lines.value[0])
    assert.equal(state.standardChoices.value.length, 1)
    state.selectStandard(optionId)
    state.applyLine()
    state.reason.value = '补选'
    await state.save()
    assert.deepEqual(env.calls.find((call) => call[0] === 'save')[2].updates, [
      { lineId, optionId }
    ])
  } finally {
    env.stop()
  }
  const fixed = component('detail', {
    api: {
      getEstimate: async () =>
        detail({
          lines: [
            line({
              source: 'STANDARD',
              optionId,
              unit: 'SET',
              quantity: '1',
              quantitySource: 'FIXED_ONE'
            })
          ]
        })
    }
  })
  try {
    await fixed.state.load()
    await flush()
    fixed.state.openLine(fixed.state.lines.value[0])
    assert.equal(fixed.state.editing.value.quantityRule, 'FIXED_ONE')
  } finally {
    fixed.stop()
  }
  const templates = component()
  try {
    await templates.state.load()
    await flush()
    templates.state.openTemplateAddition()
    await flush()
    templates.state.templateOptionId.value = optionId
    templates.state.addTemplate()
    templates.state.openTemplateAddition()
    await flush()
    templates.state.templateOptionId.value = optionId
    templates.state.addTemplate()
    assert.match(templates.state.editorError.value, /重复/)
    assert.equal(templates.state.lines.value.length, 2)
  } finally {
    templates.stop()
  }
})

test('临时项工作副本先缺价保存再补价，分类小计由0更新，原快照不变', async () => {
  const env = component()
  try {
    const state = env.state
    await state.load()
    state.openCustom()
    Object.assign(state.editing.value, {
      itemCode: 'EXTRA',
      publicName: '附加服务',
      category: 'BODY',
      unit: 'ITEM',
      quantityText: '1',
      priceYuan: ''
    })
    state.applyLine()
    assert.equal(state.previewResult.value.totalCents, null)
    assert.equal(state.previewResult.value.categoryTotals.BODY, 0)
    const temporary = state.lines.value.find((row) => !row.original)
    state.openLine(temporary)
    state.editing.value.priceYuan = '0.29'
    state.applyLine()
    assert.equal(state.previewResult.value.categoryTotals.BODY, 29)
    assert.equal(state.previewResult.value.pricedSubtotalCents, 2532)
    assert.equal(state.detail.value.lines.length, 1)
    state.reason.value = '增加服务'
    await state.save()
    assert.equal(env.calls.find((call) => call[0] === 'save')[2].additions[0].unitPriceCents, 29)
  } finally {
    env.stop()
  }
})

test('另存模板真实动作需要edit与configure权限，创建私有禁用草稿不改预算修订且不带量价', async () => {
  const env = component()
  try {
    const state = env.state
    await state.load()
    state.openTemplate(state.lines.value[0])
    Object.assign(state.templateForm, { code: 'DRAIN_TEMPLATE', name: '排水模板', reason: '复用' })
    await state.saveTemplate()
    const call = env.calls.find((call) => call[0] === 'template')
    assert.deepEqual(call[3], {
      expectedVersion: 2,
      code: 'DRAIN_TEMPLATE',
      name: '排水模板',
      reason: '复用'
    })
    assert.equal(state.detail.value.currentVersion, 2)
    assert.match(env.notices[0], /禁用私有模板草稿/)
  } finally {
    env.stop()
  }
  const denied = component('detail', { permissions: ['design:budget:query', 'design:budget:edit'] })
  try {
    await denied.state.load()
    denied.state.openTemplate(denied.state.lines.value[0])
    await denied.state.saveTemplate()
    assert.equal(denied.state.templateVisible.value, false)
    assert.equal(
      denied.calls.some((call) => call[0] === 'template'),
      false
    )
  } finally {
    denied.stop()
  }
})

test('详情迟到响应/保存结果在身份变化或卸载后丢弃，读取失败不伪装成功空数据', async () => {
  let resolveRead
  const env = component('detail', {
    api: {
      getEstimate: () =>
        new Promise((resolve) => {
          resolveRead = resolve
        })
    }
  })
  try {
    const pending = env.state.load()
    env.setTenant('2')
    resolveRead(detail())
    await pending
    assert.equal(env.state.detail.value, null)
  } finally {
    env.stop()
  }
  let resolveSave
  const writing = component('detail', {
    api: {
      createRevision: () =>
        new Promise((resolve) => {
          resolveSave = resolve
        })
    }
  })
  try {
    await writing.state.load()
    writing.state.lines.value[0].quantityText = '3'
    writing.state.reason.value = '补量'
    const pending = writing.state.save()
    writing.setTenant('2')
    resolveSave(detail({ currentVersion: 3 }))
    await pending
    assert.equal(writing.state.detail.value, null)
    assert.equal(writing.notices.length, 0)
  } finally {
    writing.stop()
  }
  const failed = component('detail', {
    api: {
      getEstimate: async () => {
        throw Error('forbidden')
      }
    }
  })
  try {
    await failed.state.load()
    assert.equal(failed.state.loadError.value, 'forbidden')
    assert.equal(failed.state.detail.value, null)
  } finally {
    failed.stop()
  }
  let finishRead
  const removed = component('detail', {
    api: {
      getEstimate: () =>
        new Promise((resolve) => {
          finishRead = resolve
        })
    }
  })
  const oldRead = removed.state.load()
  removed.stop()
  finishRead(detail())
  await oldRead
  assert.equal(removed.state.detail.value, null)
})

test('显式当前revisionId成功保存后URL规范为当前预算；历史切换/退出保护未保存工作副本', async () => {
  const env = component('detail', {
    query: { revisionId },
    api: { getEstimate: async () => detail() }
  })
  try {
    await env.state.load()
    env.state.lines.value[0].quantityText = '3'
    env.state.reason.value = '补量'
    await env.state.save()
    await flush()
    assert.deepEqual(env.calls.find((call) => call[0] === 'replace')[1], {
      path: '/zs/budget-estimates/' + budgetId,
      query: {}
    })
    assert.equal(env.route.query.revisionId, undefined)
  } finally {
    env.stop()
  }
  const dirty = component()
  try {
    await dirty.state.load()
    dirty.state.lines.value[0].quantityText = '3'
    dirty.cancelConfirm()
    assert.equal(dirty.guards.length, 2)
    assert.equal(await dirty.guards[0](), false)
    assert.equal(await dirty.guards[1](), false)
    await dirty.state.viewRevision('9007199254741000')
    await dirty.state.back()
    assert.equal(
      dirty.calls.some((call) => call[0] === 'push'),
      false
    )
    dirty.state.saving.value = true
    assert.equal(await dirty.guards[0](), false)
  } finally {
    dirty.stop()
  }
})

test('历史修订对比读取精确ID、显示持久快照差异，失败可识别且不改变当前工作副本', async () => {
  const env = component('detail', {
    api: {
      getEstimate: async (id, rev) =>
        detail(
          rev
            ? { revisionId: rev, revisionNo: 1, readOnly: true, lines: [line({ quantity: '1' })] }
            : {}
        )
    }
  })
  try {
    await env.state.load()
    env.state.lines.value[0].quantityText = '4'
    await env.state.compare('9007199254741000')
    assert.equal(env.state.diffRows.value.length, 1)
    assert.match(env.state.diffRows.value[0].newValue, /^2.5/)
    assert.equal(env.state.lines.value[0].quantityText, '4')
    env.api.getEstimate = async () => {
      throw Error('offline')
    }
    await env.state.compare('9007199254741000')
    assert.equal(env.state.compareError.value, 'offline')
  } finally {
    env.stop()
  }
})
