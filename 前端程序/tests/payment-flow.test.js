const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../miniprogram');
const plain = value => JSON.parse(JSON.stringify(value));
const flush = () => new Promise(resolve => setImmediate(resolve));
const orderId = '9007199254740993';
const payParams = { timeStamp: '1', nonceStr: 'nonce', package: 'prepay_id=x', signType: 'RSA', paySign: 'sig' };
const tag = (calls, name) => calls.filter(c => c[0] === name);

// 复用 budget-pages.test.js 的隔离口径：把 pages/wallet/recharge.js 装进 vm，
// protectedPage 捕获页面定义，wx / api 由桩顶替，依赖 record-view→format 走真实递归加载。
function runtime(initial = {}) {
  const calls = [];
  const modules = new Map();
  let definition;
  const state = {
    plans: initial.plans || [{ planId: 'plan-1', amountCents: 1000, basePoints: 100, bonusPoints: 10, recommended: true }],
    order: initial.order !== undefined ? initial.order : { orderId },
    createError: initial.createError || null,
    resumeParams: initial.resumeParams !== undefined ? initial.resumeParams : payParams,
    resumeError: initial.resumeError || null,
  };
  const api = {
    getPointAccount: async () => ({ availablePoints: 500 }),
    listRechargePlans: async () => state.plans,
    createRechargeOrder: async (planId, idemKey) => {
      calls.push(['createOrder', planId, idemKey]);
      if (state.createError) throw state.createError;
      return state.order;
    },
    getPayParams: async (id) => {
      calls.push(['getPayParams', id]);
      if (state.resumeError) throw state.resumeError;
      return state.resumeParams;
    },
  };
  const wx = {
    navigateTo: options => calls.push(['navigateTo', options]),
    showToast: options => calls.push(['toast', options]),
    showModal: options => calls.push(['modal', options]),
    requestPayment: options => calls.push(['requestPayment', options]),
  };
  function load(relative) {
    const filename = path.resolve(root, relative);
    if (modules.has(filename)) return modules.get(filename).exports;
    const module = { exports: {} };
    modules.set(filename, module);
    vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
      module, wx, console, getApp: () => ({ globalData: {} }), getCurrentPages: () => [],
      Page(value) { definition = value; },
      require(specifier) {
        if (specifier.endsWith('/access')) return { protectedPage(value) { definition = value; } };
        if (specifier.endsWith('/api')) return api;
        if (specifier.endsWith('/request')) return { getToken: () => 'session-owner-A' };
        return load(path.resolve(path.dirname(filename), specifier + '.js'));
      },
    }, { filename });
    return module.exports;
  }
  return {
    calls, api, state,
    page(options) {
      load('pages/wallet/recharge.js');
      const page = Object.assign({}, definition, { data: plain(definition.data), setData(v) { Object.assign(this.data, v); } });
      page.onLoad(options);
      return page;
    },
  };
}

// 拉起真实支付通道：下单返回带 payParams 的订单，pay() 后 requestPayment 被调用一次。
async function paidPage(env) {
  const page = env.page();
  await flush();
  page.pay();
  await flush();
  return page;
}

test('Stub 通道（订单无 payParams）直接进查单页，不拉起微信支付', async () => {
  const env = runtime();
  const page = await paidPage(env);
  assert.deepEqual(tag(env.calls, 'createOrder').map(c => c[1]), ['plan-1']);
  assert.equal(tag(env.calls, 'requestPayment').length, 0);
  assert.equal(tag(env.calls, 'navigateTo')[0][1].url, '/pages/payment/success?orderId=' + orderId);
  assert.equal(page.data.paying, false);
});

test('真实通道拉起 requestPayment，success 只跳查单页、不重复建单、不直接加点（§8 红线3）', async () => {
  const env = runtime({ order: { orderId, payParams } });
  const page = await paidPage(env);
  const rp = tag(env.calls, 'requestPayment');
  assert.equal(rp.length, 1);
  rp[0][1].success();
  await flush();
  assert.equal(tag(env.calls, 'navigateTo')[0][1].url, '/pages/payment/success?orderId=' + orderId);
  // success 分支只跳查单页，不得再次建单或调用任何加点/重领端点
  assert.equal(tag(env.calls, 'createOrder').length, 1);
  assert.equal(tag(env.calls, 'getPayParams').length, 0);
  assert.equal(page.data.paying, false);
});

test('支付进行中二次点击被防重拦截，只建单一次且携带幂等键', async () => {
  const env = runtime();
  const page = env.page();
  await flush();
  page.pay();
  page.pay();
  page.pay();
  await flush();
  const created = tag(env.calls, 'createOrder');
  assert.equal(created.length, 1);
  assert.match(created[0][2], /^recharge-\d+-[a-z0-9]+$/);
});

test('支付被用户取消时弹「继续支付」并复位 paying，不当失败处理', async () => {
  const env = runtime({ order: { orderId, payParams } });
  const page = await paidPage(env);
  tag(env.calls, 'requestPayment')[0][1].fail({ errMsg: 'requestPayment:fail cancel' });
  await flush();
  assert.equal(page.data.paying, false);
  const modal = tag(env.calls, 'modal');
  assert.equal(modal.length, 1);
  assert.equal(modal[0][1].title, '继续支付');
});

test('支付参数签名失效时提示过期并允许继续支付', async () => {
  const env = runtime({ order: { orderId, payParams } });
  const page = await paidPage(env);
  tag(env.calls, 'requestPayment')[0][1].fail({ errMsg: 'requestPayment:fail 支付参数已失效' });
  await flush();
  assert.equal(page.data.paying, false);
  assert.ok(tag(env.calls, 'toast').some(c => /过期/.test(c[1].title)));
  assert.equal(tag(env.calls, 'modal').length, 1);
});

test('支付网络错误只提示从充值记录继续，不自动弹窗重领（避免循环）', async () => {
  const env = runtime({ order: { orderId, payParams } });
  const page = await paidPage(env);
  tag(env.calls, 'requestPayment')[0][1].fail({ errMsg: 'requestPayment:fail 网络异常' });
  await flush();
  assert.equal(page.data.paying, false);
  assert.ok(tag(env.calls, 'toast').some(c => /充值记录/.test(c[1].title)));
  assert.equal(tag(env.calls, 'modal').length, 0);
});

test('继续支付确认后走独立端点重领 payParams，不重复建单，并再次拉起支付', async () => {
  const env = runtime({ order: { orderId, payParams } });
  await paidPage(env);
  tag(env.calls, 'requestPayment')[0][1].fail({ errMsg: 'requestPayment:fail cancel' });
  await flush();
  tag(env.calls, 'modal')[0][1].success({ confirm: true });
  await flush();
  assert.deepEqual(tag(env.calls, 'getPayParams').map(c => c[1]), [orderId]);
  assert.equal(tag(env.calls, 'createOrder').length, 1);
  assert.equal(tag(env.calls, 'requestPayment').length, 2);
});

test('继续支付弹窗选择稍后再说则不重领 payParams', async () => {
  const env = runtime({ order: { orderId, payParams } });
  await paidPage(env);
  tag(env.calls, 'requestPayment')[0][1].fail({ errMsg: 'requestPayment:fail cancel' });
  await flush();
  tag(env.calls, 'modal')[0][1].success({ confirm: false });
  await flush();
  assert.equal(tag(env.calls, 'getPayParams').length, 0);
});

test('重领 payParams 失败时提示订单状态已变更并复位 paying', async () => {
  const env = runtime({ order: { orderId, payParams }, resumeError: { msg: 'gone' } });
  const page = await paidPage(env);
  tag(env.calls, 'requestPayment')[0][1].fail({ errMsg: 'requestPayment:fail cancel' });
  await flush();
  tag(env.calls, 'modal')[0][1].success({ confirm: true });
  await flush();
  assert.equal(page.data.paying, false);
  assert.ok(tag(env.calls, 'toast').some(c => /订单状态已变更/.test(c[1].title)));
});

test('建单失败提示真实错误并复位 paying，不误跳查单页', async () => {
  const env = runtime({ createError: { msg: '余额不足' } });
  const page = env.page();
  await flush();
  page.pay();
  await flush();
  assert.equal(page.data.paying, false);
  assert.ok(tag(env.calls, 'toast').some(c => /余额不足/.test(c[1].title)));
  assert.equal(tag(env.calls, 'navigateTo').length, 0);
});

test('未选中有效充值方案时点击支付不建单', async () => {
  const env = runtime({ plans: [] });
  const page = env.page();
  await flush();
  page.pay();
  await flush();
  assert.equal(tag(env.calls, 'createOrder').length, 0);
});

test('订单缺有效 orderId 时不跳查单页，提示订单未确认', async () => {
  const env = runtime({ order: { orderId: 'invalid' } });
  const page = env.page();
  await flush();
  page.pay();
  await flush();
  assert.equal(tag(env.calls, 'navigateTo').length, 0);
  assert.ok(tag(env.calls, 'toast').some(c => /订单未确认/.test(c[1].title)));
});

// iOS 端虚拟商品（设计点）支付隔离：WXML 隐藏入口之外，pay() 必须再拦一层，
// 保证误触或缓存期点击也不会建单、不拉 requestPayment。
test('iOS 端支付被拦截：不建单、不拉 requestPayment，仅提示联系客服', async () => {
  const env = runtime();
  const page = env.page();
  await flush();
  page.data.iosBlocked = true;
  page.pay();
  await flush();
  assert.equal(tag(env.calls, 'createOrder').length, 0);
  assert.equal(tag(env.calls, 'requestPayment').length, 0);
  assert.ok(tag(env.calls, 'toast').some(c => /不支持在线充值/.test(c[1].title)));
});

test('非 iOS 平台 iosBlocked 保持 false，支付链路不受影响', async () => {
  const env = runtime();
  const page = env.page();
  await flush();
  assert.equal(page.data.iosBlocked, false);
});
