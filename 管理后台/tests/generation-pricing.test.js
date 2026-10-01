const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

function component({ permission = true, overrides = {} } = {}) {
  const calls = [], notices = []
  const api = {
    getPriceRules: async () => ({ list: [], total: 0 }),
    createPriceRule: async body => { calls.push(['create', body]); return { ruleId: '123' } },
    retirePriceRule: async id => { calls.push(['retire', id]); return true },
    ...overrides
  }
  const filename = path.resolve(__dirname, '../src/views/zs/generation-pricing/index.vue')
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const script = compileScript(descriptor, { id: 'generation-pricing-test' })
  const code = ts.transpileModule(script.content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  vm.runInNewContext(code, { module, exports: module.exports, ...vue, onMounted() {},
    useMessage: () => ({ success: x => notices.push(x), error: x => notices.push(x), confirm: async () => true }),
    require(name) {
      if (name === '@/api/zs/generation-pricing') return api
      if (name === '@/utils/permission') return { checkPermi: () => permission }
      return require(name)
    }
  }, { filename })
  const scope = vue.effectScope()
  const state = scope.run(() => module.exports.default.setup({}, { expose() {} }))
  return { state, calls, notices, stop: () => scope.stop() }
}

test('4K立面价格独立提交，不携带画幅或硬编码倍率', async () => {
  const c = component()
  c.state.openEditor()
  Object.assign(c.state.form, { stage: 'ELEVATION', resolution: '4K', unitPointCost: 80 })
  await c.state.save()
  assert.equal(c.calls.length, 1)
  const payload = c.calls[0][1]
  assert.equal(payload.stage, 'ELEVATION')
  assert.equal(payload.resolution, '4K')
  assert.equal(payload.unitPointCost, 80)
  assert.equal(payload.orientation, undefined)
  assert.equal(payload.minCount, 1)
  assert.equal(payload.maxCount, 4)
  assert.ok(Number.isFinite(Date.parse(payload.effectiveAt)))
  c.stop()
})

test('无价格、非正整数、数量范围和时间倒置均阻止保存', async () => {
  const c = component()
  for (const price of [undefined, 0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1]) {
    c.state.openEditor()
    c.state.form.unitPointCost = price
    await c.state.save()
  }
  Object.assign(c.state.form, { unitPointCost: 10, minCount: 4, maxCount: 2 })
  await c.state.save()
  Object.assign(c.state.form, { minCount: 1, maxCount: 4, effectiveAt: '2026-09-30T00:00:00Z', expiresAt: '2026-09-29T00:00:00Z' })
  await c.state.save()
  assert.equal(c.calls.length, 0)
  assert.equal(c.notices.length, 7)
  c.stop()
})

test('无管理权限不创建或停用；请求失败保留可见错误', async () => {
  const c = component({ permission: false })
  c.state.form.unitPointCost = 10
  await c.state.save()
  await c.state.retire({ ruleId: '123', status: 'ACTIVE' })
  assert.equal(c.calls.length, 0)
  c.stop()
  const failed = component({ overrides: { getPriceRules: async () => { throw Error('offline') } } })
  await failed.state.load()
  assert.equal(failed.state.loadError.value, true)
  assert.equal(failed.state.loading.value, false)
  failed.stop()
})

test('停用明确传字符串规则ID，分页传递当前筛选', async () => {
  const seen = []
  const c = component({ overrides: { getPriceRules: async params => { seen.push(params); return { list: [], total: 42 } } } })
  Object.assign(c.state.filters, { stage: 'FLAT', resolution: '2K', pageNo: 2 })
  await c.state.load()
  assert.equal(seen[0].resolution, '2K')
  assert.equal(seen[0].pageNo, 2)
  assert.equal(c.state.total.value, 42)
  await c.state.retire({ ruleId: '9007199254740993', status: 'ACTIVE', stage: 'FLAT', resolution: '2K' })
  assert.equal(c.calls[0][1], '9007199254740993')
  c.stop()
})
