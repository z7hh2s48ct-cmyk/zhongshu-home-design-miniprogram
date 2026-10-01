const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('vue/compiler-sfc')

const root = path.resolve(__dirname, '..')
const plain = (value) => JSON.parse(JSON.stringify(value))
function evaluate(source, filename, mocks = {}) {
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }
  vm.runInNewContext(code, { module, exports: module.exports, require(specifier) { return Object.hasOwn(mocks, specifier) ? mocks[specifier] : require(specifier) } }, { filename })
  return module.exports
}
const formPath = path.join(root, 'src/views/zs/budget/quote-form.ts')
const form = evaluate(fs.readFileSync(formPath, 'utf8'), formPath)
const budgetId = '9007199254740993', revisionId = '9007199254740995', quoteId = '9007199254740997'
const detail = (changes = {}) => ({ budgetId, revisionId, currentVersion: 3, revisionNo: 3, readOnly: false, completeness: 'COMPLETE', totalCents: 48202000, ...changes })

test('报价表单只提交最终价或调价之一，金额按字符串精确转分且不改系统总额', () => {
  assert.equal(form.yuanToCents('0.01'), 1)
  assert.equal(form.yuanToCents('-1800.50', true), -180050)
  assert.deepEqual(plain(form.quotePayload(detail(), 'FINAL', '483820.00', '客户确认优惠及增项')), {
    revisionId, expectedVersion: 3, reason: '客户确认优惠及增项', finalPriceCents: 48382000
  })
  assert.deepEqual(plain(form.quotePayload(detail(), 'ADJUSTMENT', '-1800.00', '审批后的项目优惠')), {
    revisionId, expectedVersion: 3, reason: '审批后的项目优惠', adjustmentCents: -180000
  })
})

test('待补、历史修订、空原因、浮点陷阱及越界最终价均不能创建报价', () => {
  assert.throws(() => form.quotePayload(detail({ completeness: 'INCOMPLETE', totalCents: null }), 'FINAL', '1', '原因'))
  assert.throws(() => form.quotePayload(detail({ revisionNo: 2, readOnly: true }), 'FINAL', '1', '原因'))
  for (const amount of ['', '1.001', '1e2', '+1', '01', '100000000.01']) assert.throws(() => form.quotePayload(detail(), 'FINAL', amount, '原因'))
  assert.throws(() => form.quotePayload(detail(), 'ADJUSTMENT', '-99999999.99', '原因'))
  assert.throws(() => form.quotePayload(detail(), 'FINAL', '1.00', ' 原因'))
})

test('报价API编码大整数路径，三个写操作显式透传幂等键', async () => {
  const calls = [], http = {
    get: async (options) => { calls.push(['get', options]); return {} },
    post: async (options) => { calls.push(['post', options]); return {} }
  }
  const filename = path.join(root, 'src/api/zs/budget-quote.ts')
  const api = evaluate(fs.readFileSync(filename, 'utf8'), filename, { '@/config/axios': { __esModule: true, default: http } })
  await api.getQuotes('1/2')
  await api.createQuote(budgetId, { revisionId, expectedVersion: 3, finalPriceCents: 1, reason: 'x' }, 'create-key')
  await api.publishQuote(quoteId, { expectedVersion: 1, reason: 'x' }, 'publish-key')
  await api.withdrawQuote(quoteId, { expectedVersion: 2, reason: 'x' }, 'withdraw-key')
  assert.equal(calls[0][1].url, '/design/v1/budget/estimates/1%2F2/quotes')
  assert.deepEqual(calls.slice(1).map((call) => call[1].headers['Idempotency-Key']), ['create-key', 'publish-key', 'withdraw-key'])
  assert.match(calls[2][1].url, /\/publish$/); assert.match(calls[3][1].url, /\/withdraw$/)
})

test('报价页面模板和脚本可编译，明确展示不可改系统总额、公开预览及发布撤回动作', () => {
  const filename = path.join(root, 'src/views/zs/budget/quote.vue'), source = fs.readFileSync(filename, 'utf8')
  const { descriptor, errors } = parse(source, { filename })
  assert.equal(errors.length, 0)
  assert.equal(compileScript(descriptor, { id: 'budget-quote-test' }).errors?.length || 0, 0)
  const rendered = compileTemplate({ id: 'budget-quote-test', filename, source: descriptor.template.content })
  assert.equal(rendered.errors.length, 0)
  assert.match(source, /系统测算总额.*不可修改/s)
  assert.match(source, /业主端同源预览/)
  assert.match(source, /QUOTE_PUBLISH|PUBLISH/)
  assert.match(source, /WITHDRAW/)
})
