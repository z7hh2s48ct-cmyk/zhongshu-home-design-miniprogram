const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

function component(submission) {
  const filename = path.join(__dirname, '../src/views/zs/review/detail.vue')
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const source = compileScript(descriptor, { id: 'review' }).content
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }, calls = [], notices = []
  const api = {
    getSubmission: async id => { calls.push(['read', id]); return submission },
    getSubmissionAsset: async (id, assetId) => { calls.push(['asset', id, assetId]); return new Blob(['test'], { type: 'image/png' }) },
    reviewDecision: async (...args) => calls.push(['decide', ...args]),
    publishSubmission: async (...args) => calls.push(['publish', ...args])
  }
  vm.runInNewContext(code, { module, exports: module.exports, ...vue, onMounted() {}, onBeforeUnmount() {}, Blob,
    URL: { createObjectURL: () => 'blob:test', revokeObjectURL() {} },
    useRoute: () => ({ params: { submissionId: '9007199254740993' }, query: {} }),
    useMessage: () => ({ confirm: async () => notices.push('confirm'), error: msg => notices.push(msg), success: msg => notices.push(msg) }),
    require(name) { if (name === 'vue') return vue; if (name === '@/api/zs') return api; throw Error(name) }
  })
  return { state: module.exports.default.setup({}, { expose() {} }), api, calls, notices, template: descriptor.template.content }
}

test('审核详情加载冻结资产，已审结只读，不能重复作出审核决定', async () => {
  const app = component({ status: 'APPROVED', allowedActions: ['VIEW'], previewAssets: [{ assetId: '41', label: '平面' }, { assetId: '42', label: '立面' }] })
  await app.state.load()
  assert.equal(app.state.canReview.value, false)
  assert.equal(app.state.previewAssets.value.length, 2)
  assert.equal(app.state.previewAssets.value[0].url, 'blob:test')
  await app.state.decide('APPROVE')
  assert.equal(app.calls.some(call => call[0] === 'decide'), false)
  assert.doesNotMatch(app.template, /联调后显示/)
})

test('退修需要意见，批准不隐式发布，独立发布前确认', async () => {
  const sub = { status: 'SUBMITTED', allowedActions: ['APPROVE', 'CHANGES_REQUESTED'], previewAssets: [] }
  const app = component(sub)
  await app.state.load()
  await app.state.decide('CHANGES_REQUESTED')
  assert.equal(app.calls.some(call => call[0] === 'decide'), false)
  app.state.comment.value = '请说明调整内容'
  await app.state.decide('APPROVE')
  assert.equal(app.calls.filter(call => call[0] === 'decide').length, 1)
  assert.equal(app.calls.some(call => call[0] === 'publish'), false)
  sub.allowedActions = ['PUBLISH']
  await app.state.publish()
  assert.ok(app.notices.includes('confirm'))
  assert.equal(app.calls.filter(call => call[0] === 'publish').length, 1)
})

test('资产失败显示错误并可重试，不用示例图代替', async () => {
  const app = component({ allowedActions: ['VIEW'], previewAssets: [{ assetId: '41', label: '平面' }] })
  app.api.getSubmissionAsset = async () => { throw Error('图纸文件不可读取') }
  await app.state.load()
  const asset = app.state.previewAssets.value[0]
  assert.equal(asset.url, '')
  assert.equal(asset.error, '图纸文件不可读取')
  app.api.getSubmissionAsset = async () => new Blob(['test'], { type: 'image/png' })
  await app.state.loadAsset(asset)
  assert.equal(asset.url, 'blob:test')
  assert.equal(asset.error, '')
})
