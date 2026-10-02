const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

const root = path.resolve(__dirname, '..')
function evaluate(source, filename, mocks = {}, globals = {}) {
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }
  vm.runInNewContext(code, { module, exports: module.exports, ...globals,
    require(specifier) { return Object.hasOwn(mocks, specifier) ? mocks[specifier] : require(specifier) }
  }, { filename })
  return module.exports
}
const formPath = path.join(root, 'src/views/zs/budget/catalog-form.ts')
const formModule = evaluate(fs.readFileSync(formPath, 'utf8'), formPath)

/** Execute the real Vue SFC setup with reactive state and stubbed I/O; this is not DOM/browser QA. */
function component({ permission = true, overrides = {} } = {}) {
  const calls = [], notices = []
  const api = {
    getCatalogPage: async () => ({ list: [], total: 0 }),
    saveCatalog: async (...args) => { calls.push(['save', ...args]); return {} },
    transitionPrice: async (...args) => { calls.push(['transition', ...args]); return {} },
    ...overrides
  }
  const filename = path.join(root, 'src/views/zs/budget/index.vue')
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const script = compileScript(descriptor, { id: 'budget-catalog-test' })
  const compiled = evaluate(script.content, filename, {
    '@/api/zs/budget': api,
    '@/utils/permission': { checkPermi: () => permission },
    './catalog-form': formModule,
    '@/utils/zsFormat': { fmtTime: (v) => (v ? String(v) : '—') },
    'element-plus': { ElMessageBox: { prompt: async () => ({ value: '测试原因' }), confirm: async () => true } }
  }, { ...vue, onMounted() {}, useMessage: () => ({ success: value => notices.push(value) }),
    crypto: require('node:crypto').webcrypto })
  const scope = vue.effectScope()
  const state = scope.run(() => compiled.default.setup({}, { expose() {} }))
  return { state, calls, notices, stop: () => scope.stop() }
}

test('金额文本按十进制转分，缺价与免费区别明确', () => {
  assert.equal(formModule.parsePrice(''), null)
  assert.equal(formModule.parsePrice('0'), 0)
  assert.equal(formModule.parsePrice('10.01'), 1001)
  assert.equal(formModule.parsePrice('0.29'), 29)
  assert.equal(formModule.formatCents(1001), '10.01')
  assert.equal(formModule.formatCents(null), '待补价')
  for (const invalid of ['-1', '1e3', '0.001', 'NaN', ' 2', '1000000.01']) assert.throws(() => formModule.parsePrice(invalid))
})

test('自定义项默认关闭且仅后台，编辑不带编码或来源并保留预期版本', () => {
  const form = formModule.createForm()
  Object.assign(form, { code: 'DRAINAGE', name: '排水沟施工' })
  const create = formModule.formPayload('items', form)
  assert.equal(create.enabled, false)
  assert.equal(create.publicSelectable, false)
  assert.equal(create.category, 'EXTERIOR')
  const edit = formModule.formPayload('items', form, { itemId: '9007199254740993', version: 4 })
  assert.equal(edit.expectedVersion, 4)
  assert.equal(edit.code, undefined)
  assert.equal(edit.source, undefined)
  assert.equal(formModule.rowId('items', { itemId: '9007199254740993' }), '9007199254740993')
})

test('草稿缺价可保存、免费需原因、失效时间必须晚于生效', () => {
  const form = formModule.createForm()
  Object.assign(form, { regionCode: 'TEST', optionId: '9007199254740993' })
  assert.equal(formModule.formPayload('prices', form).unitPriceCents, null)
  form.priceYuan = '0'
  assert.throws(() => formModule.formPayload('prices', form), /免费原因/)
  form.freeReason = '明确免收'
  assert.equal(formModule.formPayload('prices', form).unitPriceCents, 0)
  form.priceYuan = '12.34'
  assert.equal(formModule.formPayload('prices', form).freeReason, null)
  form.priceYuan = ''
  assert.equal(formModule.formPayload('prices', form).freeReason, null)
  form.effectiveAt = '2026-09-08T00:00:00+08:00'; form.expiresAt = form.effectiveAt
  assert.throws(() => formModule.formPayload('prices', form), /失效时间/)
})

test('数量来源白名单校验单位，模板不允许任意公式或猜测工程量', () => {
  const form = formModule.createForm()
  Object.assign(form, { code: 'DOOR', label: '全铝门', itemId: '9007199254740993', selectionGroup: 'DOOR', quantitySource: 'PROJECT_QUANTITY', quantityKey: 'DOOR_HOUSEHOLDS' })
  assert.throws(() => formModule.formPayload('options', form), /不匹配/)
  form.unit = 'HOUSEHOLD'
  assert.equal(formModule.formPayload('options', form).quantityKey, 'DOOR_HOUSEHOLDS')
  form.quantitySource = 'FIXED_ONE'; form.unit = 'METER'
  assert.throws(() => formModule.formPayload('options', form), /不匹配/)
  for (const unit of ['PIECE', 'HOUSEHOLD']) {
    form.unit = unit
    assert.throws(() => formModule.formPayload('options', form), /不匹配/)
  }
  for (const unit of ['ITEM', 'SET']) {
    form.unit = unit
    assert.equal(formModule.formPayload('options', form).quantitySource, 'FIXED_ONE')
  }
})

test('API保留字符串ID、分页参数和幂等头，保存不隐式发布', async () => {
  const calls = []
  const http = Object.fromEntries(['get', 'post', 'patch'].map(method => [method, async options => { calls.push([method, options]); return {} }]))
  const filename = path.join(root, 'src/api/zs/budget.ts')
  const api = evaluate(fs.readFileSync(filename, 'utf8'), filename, { '@/config/axios': { __esModule: true, default: http } })
  await api.saveCatalog('prices', '9007199254740993', { expectedVersion: 2, unitPriceCents: null }, 'same-key')
  await api.transitionPrice('9007199254740993', 'publish', { expectedVersion: 3, reason: '核定' }, 'publish-key')
  await api.getCatalogPage('prices', { regionCode: 'TEST', pageNo: 2 })
  assert.equal(calls[0][0], 'patch')
  assert.equal(calls[0][1].url, '/design/v1/budget/prices/9007199254740993')
  assert.equal(calls[0][1].headers['Idempotency-Key'], 'same-key')
  assert.equal(calls[1][1].url, '/design/v1/budget/prices/9007199254740993/publish')
  assert.equal(calls[2][1].params.pageNo, 2)
})

test('真实组件保存按钮防连点，网络失败保留表单且同体重试复用幂等键', async () => {
  let rejectRequest
  const attempts = []
  const env = component({ overrides: { saveCatalog: (...args) => { attempts.push(args); return new Promise((resolve, reject) => { rejectRequest = reject }) } } })
  try {
    const state = env.state
    state.openEditor(); Object.assign(state.form, { code: 'TEST', name: '测试地区' })
    const pending = state.save()
    await state.save()
    assert.equal(attempts.length, 1)
    rejectRequest(new Error('network timeout')); await pending
    assert.equal(state.editorVisible.value, true)
    assert.equal(state.form.name, '测试地区')
    assert.equal(env.notices.length, 0)
    const retry = state.save()
    assert.equal(attempts[1][3], attempts[0][3])
    rejectRequest(new Error('retry timeout')); await retry
  } finally { env.stop() }
})

test('真实组件价格保存只调用草稿API，发布另需确认原因和预期版本', async () => {
  const env = component()
  try {
    const state = env.state
    state.kind.value = 'prices'; state.openEditor()
    Object.assign(state.form, { regionCode: 'TEST', optionId: '9007199254740993', priceYuan: '10.01' })
    await state.save()
    assert.equal(env.calls.length, 1)
    assert.equal(env.calls[0][0], 'save')
    assert.equal(env.calls[0][3].unitPriceCents, 1001)
    assert.match(env.notices[0], /尚未发布/)
    state.openTransition({ priceId: '9007199254740995', version: 3, status: 'DRAFT' }, 'publish')
    await state.applyTransition()
    assert.equal(env.calls.length, 1)
    state.transitionReason.value = '核定培训价格'
    await state.applyTransition()
    assert.equal(env.calls[1][0], 'transition')
    assert.equal(env.calls[1][1], '9007199254740995')
    assert.equal(env.calls[1][3].expectedVersion, 3)
  } finally { env.stop() }
})

test('真实组件无权限不查询不写入，已发布价格不能打开金额编辑', async () => {
  let queries = 0
  const denied = component({ permission: false, overrides: { getCatalogPage: async () => { queries++; return { list: [], total: 0 } } } })
  try {
    await denied.state.refresh(); denied.state.openEditor(); await denied.state.save()
    assert.equal(queries, 0); assert.equal(denied.calls.length, 0); assert.equal(denied.state.editorVisible.value, false)
  } finally { denied.stop() }
  const allowed = component()
  try {
    allowed.state.kind.value = 'prices'
    allowed.state.openEditor({ priceId: '9007199254740993', status: 'PUBLISHED', version: 2 })
    assert.equal(allowed.state.editorVisible.value, false)
  } finally { allowed.stop() }
})

test('真实组件查询失败显示错误，不将失败伪装为成功空表', async () => {
  const env = component({ overrides: { getCatalogPage: async () => { throw new Error('forbidden') } } })
  try { await env.state.load(); assert.equal(env.state.loadError.value, 'forbidden'); assert.equal(env.state.loading.value, false) }
  finally { env.stop() }
})

test('目录下拉全量拉取有页数上限，超限报错而非静默返回残缺数据', async () => {
  let regionPages = 0
  const env = component({ overrides: { getCatalogPage: async (resource) => {
    if (resource !== 'regions') return { list: [], total: 0 }
    regionPages++
    return { list: [{ regionCode: `R${regionPages}` }], total: 1_000_000 }
  } } })
  try {
    await env.state.refresh()
    assert.equal(regionPages, 50, '总页数应被 50 页上限截停')
    assert.match(env.state.loadError.value, /上限/)
    assert.equal(env.state.choices.regions.length, 0, '超限时不得写入残缺下拉数据')
  } finally { env.stop() }
})

test('目录总量在页数上限内时正常拉全量并写入下拉', async () => {
  const env = component({ overrides: { getCatalogPage: async (resource, params) => {
    if (resource === 'regions') {
      return { list: params.pageNo <= 2 ? [{ regionCode: `R${params.pageNo}` }] : [], total: 2 }
    }
    return { list: [], total: 0 }
  } } })
  try {
    await env.state.refresh()
    assert.equal(env.state.loadError.value, '')
    assert.equal(env.state.choices.regions.length, 2)
  } finally { env.stop() }
})
