const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

function component(page) {
  const filename = path.join(__dirname, '../src/views/zs', page, 'index.vue')
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const source = compileScript(descriptor, { id: 'pagination' }).content
  const code = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} },
    calls = []
  const api = new Proxy(
    {},
    {
      get: (_, name) => async (query) => {
        calls.push({ name, query: { ...query } })
        return { list: [{ id: '11' }], total: 31 }
      }
    }
  )
  vm.runInNewContext(code, {
    module,
    exports: module.exports,
    ...vue,
    onMounted() {},
    useMessage: () => ({}),
    useRouter: () => ({}),
    require(name) {
      if (name === 'vue') return vue
      if (name === '@/api/zs') return api
      if (name === 'element-plus' || name === '@/utils/zsFormat') return {}
      throw new Error(name)
    }
  })
  return {
    state: module.exports.default.setup({}, { expose() {} }),
    calls,
    template: descriptor.template.content
  }
}

for (const page of [
  'case',
  'user',
  'accesscode',
  'job',
  'audit',
  'points/ledger',
  'recharge/order'
]) {
  test(`${page}: 分页组件双向绑定，翻页保留页码，筛选/页大小重置第一页`, async () => {
    const app = component(page)
    assert.match(app.template, /v-model:page-size="query.pageSize"/)
    assert.match(app.template, /@size-change="search"/)
    app.state.query.pageNo = 2
    await app.state.load()
    assert.equal(app.calls.findLast((call) => call.query.pageNo != null).query.pageNo, 2)
    app.state.query.pageSize = 20
    await app.state.search()
    assert.equal(app.calls.findLast((call) => call.query.pageNo != null).query.pageNo, 1)
    assert.equal(app.calls.findLast((call) => call.query.pageNo != null).query.pageSize, 20)
    assert.equal(app.state.total.value, 31)
  })
}

test('未到账队列由服务端筛选后分页，不在客户端删行导致总数失真', async () => {
  const app = component('recharge/order')
  app.state.activeTab.value = 'PAID_NO_CREDIT'
  await app.state.search()
  assert.equal(app.calls.at(-1).query.paidNoCredit, true)
  assert.equal(app.calls.at(-1).query.paymentState, 'SUCCEEDED')
  assert.equal(app.state.list.value.length, 1)
})
