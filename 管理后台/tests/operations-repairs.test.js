const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript, compileTemplate } = require('vue/compiler-sfc')
function component(page, api, route = { path: '/zs/case/create', query: {} }) {
  const filename = path.join(__dirname, '../src/views/zs', page.endsWith('.vue') ? page : page + '/index.vue')
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const compiled = compileScript(descriptor, { id: 'operations' })
  assert.deepEqual(compileTemplate({ source: descriptor.template.content, filename, id: 'operations', compilerOptions: { bindingMetadata: compiled.bindings } }).errors, [])
  const code = ts.transpileModule(compiled.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }, events = [], hooks = {}, timers = []
  vm.runInNewContext(code, {
    module, exports: module.exports, ...vue, Blob, FormData,
    onMounted: fn => { hooks.mount = fn }, onBeforeUnmount: fn => { hooks.unmount = fn },
    useRoute: () => route,
    useRouter: () => ({ push: route => events.push(route), replace: value => { Object.assign(route, value) } }), useMessage: () => ({ error: text => events.push(text), success() {}, info() {} }),
    URL: { createObjectURL: () => 'blob:actual-image', revokeObjectURL: value => events.push(['revoke', value]) },
    setTimeout: fn => { timers.push(fn); return timers.length }, clearTimeout() {},
    require(name) {
      if (name === 'vue') return vue
      if (name === '@/api/zs') return api
      if (name === '@/utils/zsFormat') return { fmtTime: value => value ? '北京时间' : '—', styleText: value => value }
      if (name === '@/utils/permission') return { checkPermi: () => true }
      if (name === '@/components/Echart') return {}
      if (name === '@/components/UploadFile') return {}
      if (name === 'element-plus') return { ElMessage: { error: text => events.push(text), success() {} } }
      throw Error(name)
    }
  })
  return { state: module.exports.default.setup({}, { expose() {} }), hooks, timers, events, template: descriptor.template.content }
}
test('导出进入和重进均读取服务端历史，翻页与页大小保持有效', async () => {
  const calls = []
  const api = { getExportJobPage: async q => { calls.push(q); return { list: [{ exportJobId: '99', status: 'COMPLETED' }], total: 23 } } }
  const app = component('export', api); await app.hooks.mount()
  assert.equal(app.state.jobs.value[0].exportJobId, '99')
  app.state.query.pageNo = 2; await app.state.refreshAll()
  assert.equal(calls.at(-1).pageNo, 2)
  app.state.query.pageSize = 50; await app.state.search(); assert.equal(calls.at(-1).pageNo, 1)
  const reopened = component('export', api); await reopened.hooks.mount(); assert.equal(reopened.state.jobs.value.length, 1)
  assert.match(app.template, /v-model:page-size="query.pageSize"/)
})
test('导出加载失败明确可重试；卸载后不写回或重新轮询', async () => {
  const app = component('export', { getExportJobPage: async () => { throw Error('断网') } })
  await app.hooks.mount(); assert.match(app.state.error.value, /加载失败/)
  let done; const late = component('export', { getExportJobPage: () => new Promise(resolve => { done = resolve }) })
  const pending = late.hooks.mount(); late.hooks.unmount(); done({ list: [{ status: 'PENDING' }], total: 1 }); await pending
  assert.equal(late.state.jobs.value.length, 0); assert.equal(late.timers.length, 0)
})
test('工作台从真实趋势与快照计价取值，错误不显示伪零', async () => {
  const api = { getDashboardSummary: async () => ({ aiJobsSucceededToday: 2, aiTrend: [{ day: '2026-09-09', count: 2 }] }),
    getAiJobPage: async () => ({ list: [{ jobId: '1', userId: '99', phase: 'FLAT', requestedCount: 3, totalPointCost: 30, refundedPointCost: 20, status: 'PARTIALLY_SUCCEEDED', createdAt: 1 }] }) }
  const app = component('dashboard', api); await app.hooks.mount()
  assert.equal(app.state.trendValues.value[0], 2)
  assert.equal(app.state.recentJobs.value[0].pointCost, 30)
  assert.equal(app.state.recentJobs.value[0].refundedPoints, 20)
  assert.equal(app.state.recentJobs.value[0].userName, '99')
  api.getDashboardSummary = async () => { throw Error('断网') }; await app.state.loadSummary()
  assert.equal(app.state.summaryReady.value, false); assert.equal(app.state.summaryError.value, true)
  assert.equal(app.state.trendDays.value.length, 0)
})
test('案例预览读取所点案例真实图片，错误可重试且关闭释放资源', async () => {
  const ids = []
  const api = { getCase: async id => { ids.push(id); return { caseId: id, sourceType: 'COMPANY', coverAssetId: 'c', floorPlans: [{ assetId: 'p', floorNo: 3 }] } },
    getCaseAsset: async (id, asset) => { ids.push([id, asset]); return new Blob(['image'], { type: 'image/png' }) } }
  const app = component('case', api); await app.state.openPreview({ caseId: '9007199254740993' })
  assert.equal(ids[0], '9007199254740993'); assert.equal(app.state.preview.data.caseId, ids[0])
  assert.equal(app.state.preview.assets[2].label, '3层平面'); assert.equal(app.state.preview.assets[0].url, 'blob:actual-image')
  assert.doesNotMatch(app.template, /\/zs\/review\?id/)
  app.state.clearPreview(); assert.equal(app.state.preview.assets.length, 0); assert.ok(app.events.some(e => e[0] === 'revoke'))
})
test('AI案例非法上架及混合批量上架在提交前阻止', async () => {
  let count = 0
  const app = component('case', { publishCase: async () => { count++ }, bulkCaseAction: async () => { count++ } })
  await app.state.doPublish({ sourceType: 'AI', publicationStatus: 'OFFLINE', caseId: '1' })
  app.state.list.value = [{ sourceType: 'AI', publicationStatus: 'OFFLINE', caseId: '1' }]; app.state.selectedIds.value = ['1']
  await app.state.bulk(true); assert.equal(count, 0); assert.ok(app.events.some(e => String(e).includes('投稿审核')))
})
test('公司图纸上传带真实版本、楼层和独立授权，成功后回读真实预览', async () => {
  const calls = []
  const app = component('case/create.vue', {
    getCase: async () => ({ sourceType: 'COMPANY', publicationStatus: 'DRAFT', title: '草稿', floorCount: 3, version: 7 }),
    uploadCaseImage: async (id, data) => { calls.push([id, data]); return { assetId: '9007199254740997', version: 8 } },
    getCaseAsset: async (id, asset) => { calls.push([id, asset]); return new Blob(['sanitized'], { type: 'image/png' }) },
    updateCase: async (id, data) => { calls.push([id, data]); return { version: data.version + 1 } }
  }, { path: '/zs/case/create', query: { id: '9007199254740993' } })
  await app.hooks.mount()
  const event = { target: { files: [new Blob(['png'], { type: 'image/png' })], value: 'image.png' } }
  await app.state.uploadImage(event, { key: 'FLOOR_PLAN:3', role: 'FLOOR_PLAN', floorNo: 3 })
  assert.equal(calls.length, 0)
  app.state.publicDisplay.value = true
  await app.state.uploadImage(event, { key: 'FLOOR_PLAN:3', role: 'FLOOR_PLAN', floorNo: 3 })
  assert.equal(calls[0][0], '9007199254740993')
  for (const [key, value] of Object.entries({ version: '7', role: 'FLOOR_PLAN', floorNo: '3', publicDisplay: 'true', generationReference: 'false' })) assert.equal(calls[0][1].get(key), value)
  assert.equal(app.state.loadedVersion.value, 8)
  assert.equal(app.state.images['FLOOR_PLAN:3'].assetId, '9007199254740997')
  assert.equal(app.state.images['FLOOR_PLAN:3'].url, 'blob:actual-image')
  await app.state.save(false)
  assert.equal(calls.at(-1)[1].version, 8)
  assert.equal('coverUrl' in calls.at(-1)[1], false)
  assert.doesNotMatch(app.template, /<UploadImg/)
  app.hooks.unmount()
  assert.equal(Object.keys(app.state.images).length, 0)
})

test('图纸上传期间不允许并发保存，预览失败不丢已保存关联且可重试', async () => {
  let finish, previews = 0, saves = 0
  const app = component('case/create.vue', {
    getCase: async () => ({ sourceType: 'COMPANY', version: 1, title: '案例', floorCount: 1 }),
    uploadCaseImage: () => new Promise(resolve => { finish = resolve }),
    updateCase: async () => { saves++; return { version: 99 } },
    getCaseAsset: async () => { if (++previews === 1) throw Error('读取失败'); return new Blob(['image']) }
  }, { path: '/zs/case/create', query: { id: '5' } })
  await app.hooks.mount(); app.state.publicDisplay.value = true
  const pending = app.state.uploadImage({ target: { files: [new Blob(['png'], { type: 'image/png' })], value: '' } }, { key: 'COVER', role: 'COVER', floorNo: null })
  await app.state.save(false); assert.equal(saves, 0)
  finish({ assetId: '44', version: 2 }); await pending
  assert.equal(app.state.images.COVER.error, true)
  assert.equal(app.state.images.COVER.assetId, '44')
  await app.state.previewImage('COVER'); assert.equal(app.state.images.COVER.error, false)
})

test('未保存/已上架案例、非图片或超限文件不能上传，迟到结果不污染已离开页面', async () => {
  let count = 0, finish
  const api = { getCase: async () => ({ sourceType: 'COMPANY', publicationStatus: 'PUBLISHED', version: 1 }), uploadCaseImage: () => { count++; return new Promise(resolve => { finish = resolve }) } }
  const app = component('case/create.vue', api, { path: '/zs/case/create', query: { id: '5' } })
  await app.hooks.mount(); app.state.publicDisplay.value = true
  const slot = { key: 'COVER', role: 'COVER', floorNo: null }
  const event = file => ({ target: { files: [file], value: '' } })
  await app.state.uploadImage(event(new Blob(['png'], { type: 'image/png' })), slot)
  assert.equal(count, 0)
  assert.match(app.state.loadError.value, /先返回列表下架/)
  app.state.loadError.value = ''
  app.state.loadedVersion.value = 1
  app.state.publicationStatus.value = 'DRAFT'
  await app.state.uploadImage(event(new Blob(['not image'], { type: 'text/plain' })), slot)
  await app.state.uploadImage(event({ type: 'image/png', size: 16 * 1024 * 1024 + 1 }), slot)
  assert.equal(count, 0)
  const pending = app.state.uploadImage(event(new Blob(['png'], { type: 'image/png' })), slot)
  app.hooks.unmount(); finish({ assetId: 'late', version: 2 }); await pending
  assert.equal(Object.keys(app.state.images).length, 0)
  const draft = component('case/create.vue', api); draft.state.publicDisplay.value = true
  await draft.state.uploadImage(event(new Blob(['png'], { type: 'image/png' })), slot)
  assert.equal(count, 1)
})

test('公司案例编辑先加载真实参数和版本，连续保存不使用默认版本1', async () => {
  const writes = []
  const app = component('case/create.vue', { getCase: async () => ({ sourceType: 'COMPANY', title: '已有案例', floorCount: 3, buildingArea: 230, version: 7 }),
    updateCase: async (id, data) => { writes.push([id, data]); return { version: data.version + 1 } }
  }, { path: '/zs/case/create', query: { id: '123' } })
  await app.hooks.mount(); assert.equal(app.state.form.title, '已有案例'); assert.equal(app.state.form.floorCount, 3)
  await app.state.save(false); await app.state.save(false)
  assert.equal(writes[0][1].version, 7); assert.equal(writes[1][1].version, 8)
})
test('直达AI案例编辑或详情加载失败均不能提交默认表单', async () => {
  let writes = 0
  const app = component('case/create.vue', { getCase: async () => ({ sourceType: 'AI' }), updateCase: async () => { writes++ } }, { path: '/zs/case/create', query: { id: '1' } })
  await app.hooks.mount(); await app.state.save(false)
  assert.match(app.state.loadError.value, /投稿审核/); assert.equal(writes, 0)
})
