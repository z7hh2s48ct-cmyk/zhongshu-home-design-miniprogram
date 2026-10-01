const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

test('权限模块可在Pinia安装前导入，检查权限时再读取当前用户', () => {
  const source = fs.readFileSync(path.resolve(__dirname, '../src/directives/permission/hasPermi.ts'), 'utf8')
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
  let user
  const module = { exports: {} }
  vm.runInNewContext(code, { module, exports: module.exports, useI18n: () => ({ t: key => key }),
    require: () => ({ useUserStore: () => { if (!user) throw Error('Pinia not installed'); return user } })
  })
  user = { roles: [], permissions: new Set(['commerce:generation-price:query']) }
  assert.equal(module.exports.hasPermission(['commerce:generation-price:query']), true)
  assert.equal(module.exports.hasPermission(['commerce:generation-price:manage']), false)
  user = { roles: ['super_admin'], permissions: new Set() }
  assert.equal(module.exports.hasPermission(['commerce:generation-price:manage']), true)
})
