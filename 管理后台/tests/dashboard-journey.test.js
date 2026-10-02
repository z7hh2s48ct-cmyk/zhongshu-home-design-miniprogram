const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

function component(permission) {
  const filename = path.join(__dirname, '../src/views/zs/dashboard/index.vue')
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const source = compileScript(descriptor, { id: 'dashboard-journey' }).content
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }, routes = []
  vm.runInNewContext(code, { module, exports: module.exports, ...vue, onMounted() {},
    useRouter: () => ({ push: route => routes.push(route) }),
    require(name) {
      if (name === 'vue') return vue
      if (name === '@/utils/permission') return { checkPermi: () => permission }
      if (name === '@/utils/zsFormat') return { fmtTime: value => value || '—' }
      if (['@/api/zs', '@/components/Echart'].includes(name)) return {}
      throw new Error(name)
    }
  })
  const state = module.exports.default.setup({}, { expose() {} })
  return { state, routes, template: descriptor.template.content }
}
test('工作台进行中任务与查看全部均进入流水页生成任务tab，不误入授权码或充值订单', () => {
  const app = component(true)
  app.state.todos.value.find(item => item.label === '进行中任务').go()
  app.state.openJobs()
  assert.deepEqual(app.routes, ['/zs/point-ledger?tab=jobs', '/zs/point-ledger?tab=jobs'])
  assert.match(app.template, /@click="openJobs"/)
})
test('已有预算页面从工作台直接可达，不新增重复页面', () => {
  const app = component(true)
  app.state.quickActions.value.find(item => item.label === '预算配置').go()
  app.state.quickActions.value.find(item => item.label === '项目预算与报价').go()
  assert.deepEqual(app.routes, ['/zs/budget', '/zs/budget-estimates'])
})
test('没有预算查询权限不显示预算快捷入口，原有四个业务入口保留', () => {
  const app = component(false)
  assert.equal(app.state.quickActions.value.length, 4)
  assert.equal(app.state.quickActions.value.some(item => item.label.includes('预算')), false)
})
