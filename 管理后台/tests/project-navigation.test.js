const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const pinia = require('pinia')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

function load(file, imports = {}, globals = {}) {
  const filename = path.join(__dirname, '../src', file)
  let source = fs.readFileSync(filename, 'utf8')
  if (file.endsWith('.vue')) source = compileScript(parse(source, { filename }).descriptor, { id: 'project-menu' }).content
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }
  vm.runInNewContext(code, { module, exports: module.exports, ...globals, require(name) {
    if (name in imports) return imports[name]
    throw Error(`Unexpected import: ${name}`)
  } })
  return module.exports
}
const helpers = load('utils/projectNavigation.ts')
const business = load('router/modules/zs.ts', { '@/utils/routerHelper': { Layout: () => {} } }).default
const base = [{ path: '/', name: 'Home', meta: {}, children: [{ path: 'index', meta: { title: '工作台', affix: true } }] }]
const leaf = (path, title = path) => ({ path, name: title, meta: { title } })
const authorized = [
  { path: '/zs', meta: { title: '旧业务目录' }, children: [leaf('case'), leaf('review')] },
  ...['mall', 'crm', 'erp', 'bpm', 'mp', 'iot', 'mes', 'report', 'ai', 'pay', 'member'].map(name => ({ path: `/${name}`, meta: {}, children: [leaf('index')] })),
  leaf('https://doc.iocoder.cn/', '开发文档'),
  { path: '/system', meta: { title: '系统管理' }, children: [leaf('tenant'), leaf('user'), leaf('role'), leaf('menu'), leaf('dict'), { path: 'log', meta: { title: '日志' }, children: [leaf('login-log'), leaf('operate-log')] }] },
  { path: '/infra', redirect: '/infra/codegen', meta: { title: '基础设施' }, children: [leaf('codegen'), leaf('config'), { path: 'file', meta: { title: '文件' }, children: [leaf('file'), leaf('file-config')] }, leaf('swagger')] }
]
const menus = can => helpers.buildProjectMenus(base, business, authorized, can || (() => true))
const values = (routes, query) => Array.from(helpers.searchProjectMenus(routes, query), item => item.value)

test('业务菜单按六组组装：户型最上、隐私在最后组末尾，工作台与系统/运维保留', () => {
  const before = JSON.stringify({ base, business, authorized })
  const result = menus()
  assert.deepEqual(
    Array.from(result, route => route.path),
    ['/', '/zs-content', '/zs-auth', '/zs-fund', '/zs-budget', '/zs-pricing', '/zs-ops', '/system', '/infra']
  )
  assert.deepEqual(
    Array.from(result, route => route.meta?.title).slice(1, 7),
    ['户型与内容', '用户与授权', '资金与点数', '预算与报价', '价格管理', '运营与合规']
  )
  // 隐藏路由（详情/编辑/旧充值路由）不进菜单
  assert.deepEqual(Array.from(result[1].children, child => child.path), ['/zs/case', '/zs/review'])
  assert.deepEqual(Array.from(result[2].children, child => child.path), ['/zs/account', '/zs/access-code', '/zs/point-adjustments'])
  assert.deepEqual(Array.from(result[3].children, child => child.path), ['/zs/recharge', '/zs/point-ledger'])
  assert.deepEqual(Array.from(result[4].children, child => child.path), ['/zs/budget', '/zs/budget-estimates'])
  assert.deepEqual(Array.from(result[5].children, child => child.path), ['/zs/generation-pricing', '/zs/usage-pricing'])
  assert.deepEqual(Array.from(result[6].children, child => child.path), ['/zs/ai-job', '/zs/export', '/zs/audit', '/zs/privacy'])
  assert.equal(JSON.stringify({ base, business, authorized }), before)
  // R7：管理员账号与菜单管理已从入口裁剪，系统设置首项为角色管理
  assert.equal(result[7].children.some(c => ['user', 'menu'].includes(c.path)), false)
  assert.equal(result[7].children[0].name, 'role')
  assert.equal(result[8].redirect, '/infra/config')
})

test('保留全部业务入口且子项为绝对路径，可直接导航', () => {
  for (const target of ['case', 'review', 'access-code', 'point-adjustments', 'recharge', 'budget', 'budget-estimates', 'point-ledger', 'account', 'ai-job', 'export', 'audit', 'privacy', 'generation-pricing', 'usage-pricing']) {
    assert.ok(values(menus(), `/zs/${target}`).includes(`/zs/${target}`), target)
  }
})

test('普通角色只展示已有权限对应业务组，空权限不补发业务菜单', () => {
  const limited = menus(permissions => permissions.includes('design:budget:query'))
  assert.deepEqual(
    Array.from(limited.filter(route => String(route.path).startsWith('/zs-')), route => route.path),
    ['/zs-budget']
  )
  assert.deepEqual(Array.from(limited[1].children, child => child.path), ['/zs/budget', '/zs/budget-estimates'])
  assert.equal(menus(() => false).some(route => String(route.path).startsWith('/zs-')), false)
  const noSupport = helpers.buildProjectMenus(base, business, [], () => false)
  assert.deepEqual(Array.from(noSupport, route => route.path), ['/'])
})

test('搜索不泄露无关模块、外链、隐藏详情和参数占位路由', () => {
  for (const query of ['商城', '/mall', '/crm', '/pay', '/system/tenant', 'codegen', 'http', ':budgetId', 'quotes', 'create', 'detail', '开发文档', 'zs-content']) {
    assert.equal(helpers.searchProjectMenus(menus(), query).length, 0, query)
  }
  assert.deepEqual(values(menus(), '  /ZS/BUDGET  '), ['/zs/budget', '/zs/budget-estimates'])
  assert.deepEqual(values(menus(), '充值'), ['/zs/recharge'])
  assert.equal(helpers.searchProjectMenus(menus(), ' ').length, 0)
})

test('隐藏父级及空目录不残留，绝对路径和默认子页可正确解析', () => {
  const result = helpers.buildProjectMenus(base, [], [
    { path: '/system', meta: { hidden: true }, children: [leaf('user')] },
    { path: '/infra', meta: {}, children: [leaf('codegen')] },
    { path: '/system/role', meta: {}, children: [leaf('', '角色管理')] }
  ], () => true)
  assert.deepEqual(values(result, '角色'), ['/system/role'])
  assert.equal(result.some(route => route.path === '/infra'), false)
})

test('权限 store 裁剪展示菜单，但保留后端授权动态路由，不冒充权限撤销', async () => {
  const store = pinia.createPinia()
  pinia.setActivePinia(store)
  const loaded = load('store/modules/permission.ts', {
    pinia, '@/store': { store }, 'lodash-es': { cloneDeep: value => value },
    '@/router/modules/remaining': { __esModule: true, default: base }, '@/router/modules/zs': { __esModule: true, default: business },
    '@/utils/projectNavigation': helpers, '@/utils/permission': { checkPermi: () => true },
    '@/utils/routerHelper': { generateRoute: () => authorized, flatMultiLevelRoutes: value => value },
    '@/hooks/web/useCache': { CACHE_KEY: { ROLE_ROUTERS: 'routes' }, useCache: () => ({ wsCache: { get: () => [] } }) }
  })
  const state = loaded.usePermissionStore()
  await state.generateRoutes()
  assert.equal(state.getRouters.some(route => route.path === '/mall'), false)
  assert.equal(state.getAddRouters.some(route => route.path === '/mall'), true)
  assert.ok(values(state.getRouters, '/zs/budget').includes('/zs/budget'))
})

test('真实搜索组件随权限菜单变化而更新，不扫描全部路由，选择后正常导航', () => {
  const store = vue.reactive({ getRouters: menus() })
  const pushed = []
  const component = load('components/RouterSearch/index.vue', {
    vue, '@/utils/propTypes': { propTypes: { string: { def: () => ({}) } } },
    '@/store/modules/permission': { usePermissionStore: () => store }, '@/utils/projectNavigation': helpers
  }, { ...vue, useRouter: () => ({ push: route => pushed.push(route) }), onMounted() {}, onUnmounted() {} }).default
  const state = component.setup({}, { expose() {} })
  state.remoteMethod('/zs/budget')
  assert.equal(state.options.value.length, 2)
  state.handleChange(state.options.value[0].value)
  assert.equal(pushed[0].path, '/zs/budget')
  store.getRouters = menus(() => false)
  assert.equal(state.options.value.length, 0)
})

test('顶部移除模板入口，保留账号资料、锁屏、退出、菜单搜索与全屏', () => {
  const header = fs.readFileSync(path.join(__dirname, '../src/layout/components/ToolHeader.vue'), 'utf8')
  const user = fs.readFileSync(path.join(__dirname, '../src/layout/components/UserInfo/src/UserInfo.vue'), 'utf8')
  const layout = fs.readFileSync(path.join(__dirname, '../src/layout/Layout.vue'), 'utf8')
  assert.doesNotMatch(header, /TenantVisit|goToChat|ImHome|<Message|<LocaleDropdown|<SizeDropdown|openSetting/)
  assert.match(header, /<RouterSearch/)
  assert.match(header, /<Screenfull/)
  assert.doesNotMatch(layout, /<Setting/)
  assert.doesNotMatch(user, /doc\.iocoder\.cn|toDocument/)
  for (const action of ['toProfile', 'lockScreen', 'loginOut']) assert.ok(user.includes(action))
})
