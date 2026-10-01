const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')
function component(api, id = '901') {
  const filename = path.join(__dirname, '../src/views/zs/recharge/plan-edit.vue')
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const compiled = compileScript(descriptor, { id: 'plan-edit-regression' })
  const code = ts.transpileModule(compiled.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }; let mount; let back = 0
  vm.runInNewContext(code, { module, exports: module.exports, ...vue,
    onMounted: fn => { mount = fn }, useRoute: () => ({ query: id ? { id } : {} }),
    useMessage: () => ({ error() {}, success() {} }), history: { back: () => { back++ } },
    require: name => name === 'vue' ? vue : name === '@/api/zs' ? api : null })
  return { state: module.exports.default.setup({}, { expose() {} }), mount: () => mount(), back: () => back }
}
const fixture = { id: '901', name: '停用原套餐', amountCents: 9900, basePoints: 999, bonusPoints: 99, recommended: true, sort: 42, enabled: false }
test('edit loads actual immutable price and name-only update preserves disabled/recommended/sort', async () => {
  const patches = []; const app = component({ getPlan: async () => fixture, updatePlan: async (...args) => patches.push(args) })
  await app.mount(); assert.equal(app.state.amountYuan.value, 99); assert.equal(app.state.form.basePoints, 999)
  assert.equal(app.state.form.bonusPoints, 99); assert.equal(app.state.form.enabled, false)
  app.state.form.name = '仅改名称'; await app.state.save()
  assert.equal(patches[0][0], '901'); assert.deepEqual(JSON.parse(JSON.stringify(patches[0][1])), { name: '仅改名称' })
})
test('failed or wrong-ID detail cannot save defaults; retry permits only intentional fields', async () => {
  const patches = []; let fail = true
  const app = component({ getPlan: async () => fail ? { ...fixture, id: '902' } : fixture, updatePlan: async (...args) => patches.push(args) })
  await app.mount(); app.state.form.name = 'blocked'; await app.state.save(); assert.equal(patches.length, 0)
  fail = false; await app.state.loadPlan(); app.state.form.recommended = false; app.state.form.sort = 43; await app.state.save()
  assert.deepEqual(JSON.parse(JSON.stringify(patches[0][1])), { recommended: false, sort: 43 })
})
test('new plan keeps explicit creation price and fields', async () => {
  const creates = []; const app = component({ createPlan: async body => creates.push(body) }, null)
  await app.mount(); app.state.form.name = '新套餐'; app.state.amountYuan.value = 99; await app.state.save()
  assert.equal(creates[0].amountCents, 9900); assert.equal(creates[0].enabled, true)
})
