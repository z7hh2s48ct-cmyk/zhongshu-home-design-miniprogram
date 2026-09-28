const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

function evaluate(file, env, mocks, globals = {}) {
  const source = fs.readFileSync(path.join(__dirname, '../src', file), 'utf8').replaceAll('import.meta.env', 'testEnv')
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }
  vm.runInNewContext(code, { module, exports: module.exports, testEnv: env, console, ...globals,
    require(name) { if (Object.hasOwn(mocks, name)) return mocks[name]; throw new Error('Unmocked: ' + name) }
  })
  return module.exports
}
function auth(env, cachedTenant = 99) {
  return evaluate('utils/auth.ts', env, {
    '@/hooks/web/useCache': { useCache: () => ({ wsCache: { get: () => cachedTenant } }), CACHE_KEY: { TenantId: 'tenant' } }
  })
}
function transport(env) {
  let request, response
  const posts = []
  const client = async config => config
  client.interceptors = { request: { use: fn => { request = fn } }, response: { use: fn => { response = fn } } }
  const axios = { create: () => client, defaults: { headers: { common: {} } }, post: async (...args) => {
    posts.push(args); return { data: { data: { accessToken: 'refreshed', refreshToken: 'refresh' } } }
  } }
  evaluate('config/axios/service.ts', env, {
    axios, 'element-plus': {}, qs: {}, '@/config/axios/config': { config: { base_url: '/admin-api', result_code: 0 } },
    '@/utils/auth': { ...auth(env), getAccessToken: () => 'test-token', getRefreshToken: () => 'test-refresh', getVisitTenantId: () => 88, setToken() {} },
    './errorCode': {}, '@/router': {}, '@/hooks/web/useCache': {}, '@/utils/encrypt': { ApiEncrypt: { getEncryptHeader: () => 'x-encrypted' } }
  }, { useI18n: () => ({ t: value => value }) })
  return { request, response, posts, axios }
}

test('单租户使用部署配置，不继承旧的其他租户缓存', () => {
  assert.equal(auth({ VITE_APP_TENANT_ENABLE: 'false', VITE_APP_TENANT_ID: '1' }).getTenantId(), '1')
  assert.equal(auth({ VITE_APP_TENANT_ENABLE: 'false', VITE_APP_TENANT_ID: '42' }).getTenantId(), '42')
  assert.equal(auth({ VITE_APP_TENANT_ENABLE: 'false' }).getTenantId(), undefined)
})
test('多租户保持选择的租户，不被单租户配置覆盖', () => {
  assert.equal(auth({ VITE_APP_TENANT_ENABLE: 'true', VITE_APP_TENANT_ID: '1' }).getTenantId(), 99)
})
test('真实请求拦截器：单租户登录带租户，不带旧令牌或跨租户访问头', () => {
  const app = transport({ VITE_APP_TENANT_ENABLE: 'false', VITE_APP_TENANT_ID: '1' })
  const request = app.request({ url: '/system/auth/login', method: 'post', headers: {} })
  assert.equal(request.headers['tenant-id'], '1')
  assert.equal(request.headers.Authorization, undefined)
  assert.equal(request.headers['visit-tenant-id'], undefined)
})
test('真实请求拦截器：多租户受保护请求保留授权与访问租户', () => {
  const app = transport({ VITE_APP_TENANT_ENABLE: 'true', VITE_APP_TENANT_ID: '1' })
  const request = app.request({ url: '/design/v1/budget/regions', method: 'get', headers: {} })
  assert.equal(request.headers['tenant-id'], 99)
  assert.equal(request.headers['visit-tenant-id'], 88)
  assert.equal(request.headers.Authorization, 'Bearer test-token')
})
test('401刷新与原请求使用同一租户，且不污染全局 axios 默认头', async () => {
  const app = transport({ VITE_APP_TENANT_ENABLE: 'false', VITE_APP_TENANT_ID: '42' })
  await app.response({ data: { code: 401 }, config: { headers: {} }, headers: {}, request: {} })
  assert.equal(app.posts.length, 1)
  assert.equal(app.posts[0][2].headers['tenant-id'], '42')
  assert.equal(app.axios.defaults.headers.common['tenant-id'], undefined)
})

test('页面权限与后端超级管理员规则一致，普通账号无预算权限仍拒绝', () => {
  const user = { roles: [], permissions: new Set() }
  const permission = evaluate('directives/permission/hasPermi.ts', {}, {
    '@/store/modules/user': { useUserStore: () => user }
  }, { useI18n: () => ({ t: value => value }) })
  assert.equal(permission.hasPermission(['design:budget:query']), false)
  user.roles = ['admin']
  assert.equal(permission.hasPermission(['design:budget:query']), false)
  user.permissions.add('design:budget:query')
  assert.equal(permission.hasPermission(['design:budget:query']), true)
  assert.equal(permission.hasPermission(['design:budget:configure']), false)
  user.permissions.clear(); user.roles = ['super_admin']
  assert.equal(permission.hasPermission(['design:budget:query']), true)
  user.roles = []
  assert.equal(permission.hasPermission(['design:budget:query']), false)
  user.permissions.add('*:*:*')
  assert.equal(permission.hasPermission(['design:budget:query']), true)
})
