const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript, compileTemplate } = require('vue/compiler-sfc')

const root = path.resolve(__dirname, '..')
const flush = () => new Promise((resolve) => setImmediate(resolve))

/**
 * T13-30 前半 管理端「充值订单」页面测试（复用 operations-repairs.test.js 的
 * node:test + vue/compiler-sfc + vm 沙箱模式，无需 vitest/jsdom）。
 *
 * 编译 src/views/zs/recharge/order/index.vue 的 <script setup>，在 VM 中运行 setup()
 * 拿到响应式状态与方法直接驱动，并对模板源码做契约断言。覆盖分派表 §3.3 ①a/①b/①c/③。
 */
function component(api, message) {
  const filename = path.join(root, 'src/views/zs/recharge/order/index.vue')
  const source = fs.readFileSync(filename, 'utf8')
  const { descriptor, errors } = parse(source, { filename })
  assert.equal(errors.length, 0)
  const compiled = compileScript(descriptor, { id: 'recharge-order-test' })
  assert.deepEqual(
    compileTemplate({
      source: descriptor.template.content,
      filename,
      id: 'recharge-order-test',
      compilerOptions: { bindingMetadata: compiled.bindings }
    }).errors,
    []
  )
  const code = ts.transpileModule(compiled.content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  const hooks = {}
  const msgs = message || { confirm: async () => {}, success() {}, error() {} }
  vm.runInNewContext(
    code,
    {
      module,
      exports: module.exports,
      ...vue,
      onMounted: (fn) => {
        hooks.mount = fn
      },
      useMessage: () => msgs,
      require(name) {
        if (name === 'vue') return vue
        if (name === '@/api/zs') return api
        if (name === '@/utils/zsFormat') return { fmtTime: (v) => (v ? '北京时间' : '—') }
        throw Error('unexpected require: ' + name)
      }
    },
    { filename }
  )
  return {
    state: module.exports.default.setup({}, { expose() {} }),
    hooks,
    template: descriptor.template.content
  }
}

test('①a 订单详情弹窗下发并展示真实渠道字段（渠道/商户号/渠道流水号）', async () => {
  const detail = {
    orderId: '1',
    orderNo: 'NO-1',
    userId: '9',
    amountCents: 1000,
    basePoints: 100,
    bonusPoints: 20,
    paymentState: 'SUCCEEDED',
    fulfillmentState: 'CREDITED',
    channel: 'WECHAT',
    merchantId: '1600000001',
    channelTransactionId: '4200001234202609110001',
    channelPaidAt: '2026-09-11T10:00:00'
  }
  const app = component({ getOrder: async () => detail })
  await app.state.openDetail({ orderId: '1', id: '1' })

  assert.equal(app.state.detail.visible, true)
  assert.equal(app.state.detail.data.channel, 'WECHAT')
  assert.equal(app.state.detail.data.merchantId, '1600000001')
  assert.equal(app.state.detail.data.channelTransactionId, '4200001234202609110001')
  assert.equal(app.state.channelText('WECHAT'), '微信支付')
  assert.equal(app.state.channelText('STUB'), 'Stub(测试)')
  // 模板契约：详情弹窗必须含三项渠道字段标签，且用 v-if 守卫（未支付不渲染）
  assert.match(app.template, /label="支付渠道"/)
  assert.match(app.template, /label="商户号"/)
  assert.match(app.template, /label="渠道流水号"/)
  assert.match(app.template, /v-if="detail\.data\.channel"/)
})

test('①a 边界：未支付订单详情不含渠道字段（避免误导财务）', async () => {
  const app = component({
    getOrder: async () => ({
      orderId: '2',
      orderNo: 'NO-2',
      paymentState: 'PENDING',
      fulfillmentState: 'NOT_READY'
    })
  })
  await app.state.openDetail({ orderId: '2', id: '2' })

  assert.equal(app.state.detail.data.channel, undefined)
  assert.equal(app.state.detail.data.channelTransactionId, undefined)
})

test('①b 整单退款同步置位守卫，双击只发起一次退款请求，完成后复位', async () => {
  let calls = 0
  let releaseRefund
  const api = {
    createRefundRequest: async () => {
      calls++
      return new Promise((resolve) => {
        releaseRefund = resolve
      })
    },
    getOrderPage: async () => ({ list: [], total: 0 })
  }
  const app = component(api)
  const row = { orderId: '7', id: '7', orderNo: 'NO-7' }

  const p1 = app.state.doRefund(row)
  const p2 = app.state.doRefund(row) // 双击第二次：refundingId 已同步置位 → 立即返回
  assert.equal(app.state.refundingId.value, '7')

  await flush() // 放行 confirm 微任务，p1 进入 createRefundRequest 后挂起
  assert.equal(calls, 1) // 仅一次真实退款请求（后端 refund_request_key 幂等之外的前端守卫）

  releaseRefund({})
  await Promise.all([p1, p2])
  assert.equal(app.state.refundingId.value, null) // 完成后复位，可再次受理
})

test('①b 用户取消确认时静默中止并复位守卫，不发起退款', async () => {
  let calls = 0
  const api = {
    createRefundRequest: async () => {
      calls++
      return {}
    }
  }
  const app = component(api, {
    confirm: async () => {
      throw 'cancel'
    },
    success() {},
    error() {
      throw new Error('取消不应触发 error toast')
    }
  })
  await app.state.doRefund({ orderId: '8', id: '8', orderNo: 'NO-8' })

  assert.equal(calls, 0)
  assert.equal(app.state.refundingId.value, null)
})

test('①c 支付成功未到账判定为真，模板渲染橙色高亮标签', () => {
  const app = component({})
  assert.equal(
    app.state.isPaidNoCredit({ paymentState: 'SUCCEEDED', fulfillmentState: 'PENDING' }),
    true
  )
  assert.equal(
    app.state.isPaidNoCredit({ paymentState: 'SUCCEEDED', fulfillmentState: 'NOT_READY' }),
    true
  )
  assert.equal(
    app.state.isPaidNoCredit({ paymentState: 'SUCCEEDED', fulfillmentState: 'CREDITED' }),
    false
  )
  assert.equal(
    app.state.isPaidNoCredit({ paymentState: 'PENDING', fulfillmentState: 'NOT_READY' }),
    false
  )
  assert.match(app.template, /isPaidNoCredit\(row\)/)
  assert.match(app.template, /zs-tag--orange/)
  assert.match(app.template, /未到账/)
})

test('③ 渠道流水 tab 调用 payment-transactions 分页并渲染渠道资金事实', async () => {
  const calls = []
  const api = {
    getPaymentTransactionPage: async (q) => {
      calls.push(q)
      return {
        list: [
          {
            orderNo: 'NO-1',
            channel: 'WECHAT',
            merchantId: '1600000001',
            channelTransactionId: 'txn-1',
            amountCents: 1000,
            paidAt: '2026-09-11T10:00:00'
          }
        ],
        total: 1
      }
    }
  }
  const app = component(api)
  app.state.activeTab.value = 'TRANSACTIONS'
  await app.state.load()

  assert.equal(calls.length, 1)
  assert.equal(app.state.list.value[0].channelTransactionId, 'txn-1')
  assert.equal(app.state.list.value[0].channel, 'WECHAT')
  assert.equal(app.state.total.value, 1)
  assert.match(app.template, /label="渠道流水"/)
  assert.match(app.template, /name="TRANSACTIONS"/)
})

test('状态色 helper 输出合法 zs-tag-- 变体类（回归订单表配色约定）', () => {
  const app = component({})
  assert.equal(app.state.payColor('SUCCEEDED'), 'zs-tag--green')
  assert.equal(app.state.payColor('PENDING'), 'zs-tag--orange')
  assert.equal(app.state.payColor('FAILED'), 'zs-tag--red')
  assert.equal(app.state.payColor('XXX'), 'zs-tag--gray')
  assert.equal(app.state.fulColor('CREDITED'), 'zs-tag--green')
  assert.equal(app.state.fulColor('FAILED'), 'zs-tag--red')
})
