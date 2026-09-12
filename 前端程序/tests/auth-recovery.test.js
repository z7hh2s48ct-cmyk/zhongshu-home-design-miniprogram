const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../miniprogram');
const tick = () => new Promise((resolve) => setImmediate(resolve));

// T13-07 会话恢复专项。两种驱动模式：
//  A) API-mock：覆写 api.login/getAccessGrant/redeemAccessCode，聚焦激活页自身逻辑；
//  B) 真实请求层：不覆写 api，改由 wx.request 驱动，令 api→request.js 真实链路参与
//     （会话代际 SESSION_CHANGED、401→刷新→清会话），验证恢复与请求层协同无误。
function runtime(initialStorage = {}, opts = {}) {
  const calls = [];
  const requests = [];
  const storage = Object.assign({}, initialStorage);
  const pages = [];
  const modules = new Map();
  let definition;
  let app;
  let loginCalls = 0;
  const wx = {
    getStorageSync(key) { return storage[key]; },
    setStorageSync(key, value) { storage[key] = value; },
    removeStorageSync(key) { delete storage[key]; },
    showToast(options) { calls.push(['toast', options]); },
    showModal(options) { calls.push(['modal', options]); },
    navigateTo(options) { calls.push(['navigateTo', options]); options.success?.(); },
    redirectTo(options) { calls.push(['redirectTo', options]); options.success?.(); },
    switchTab(options) { calls.push(['switchTab', options]); options.success?.(); },
    navigateBack(options = {}) { calls.push(['navigateBack', options]); pages.pop(); options.success?.(); }, // 真实卸载：出栈当前页，令 getCurrentPages 反映离页
    login(options) { loginCalls++; options.success?.({ code: 'wx-temp-code' }); },
  };
  // 仅 B) 真实请求层用例注入 wx.request，驱动 api→request.js 真实链路（会话代际 / 401 刷新 / 兑换）。
  // A) API-mock 用例不注入 wx.request：令 access.refreshFromServer 静默跳过（与 activation-gate 环境一致），
  //    隔离激活页自身逻辑，避免 activate 后的授权对账异步回写干扰断言。
  if (opts.realRequest) wx.request = (options) => requests.push(options);
  function load(filename) {
    const fullPath = path.resolve(root, filename);
    if (modules.has(fullPath)) return modules.get(fullPath).exports;
    const module = { exports: {} };
    modules.set(fullPath, module);
    const context = vm.createContext({
      wx, module, exports: module.exports, console,
      getCurrentPages: () => pages,
      getApp: () => app,
      App(value) { app = value; },
      Page(value) { definition = value; },
      Component() {},
      require(specifier) {
        const target = path.resolve(path.dirname(fullPath), specifier);
        if (target.endsWith('.json')) return JSON.parse(fs.readFileSync(target, 'utf8'));
        return load(target.endsWith('.js') ? target : `${target}.js`);
      },
    });
    vm.runInContext(fs.readFileSync(fullPath, 'utf8'), context, { filename: fullPath });
    return module.exports;
  }
  load('app.js');
  app.homeSeen = true; // 激活页要求已看过首页；否则 onLoad 直接回首页
  function authPage(query = {}) {
    load('pages/auth/index.js');
    const instance = {
      ...definition, route: 'pages/auth/index', options: query,
      data: JSON.parse(JSON.stringify(definition.data || {})),
      setData(value) { Object.assign(this.data, value); },
    };
    pages.push(instance);
    instance.onLoad?.(query);
    return instance;
  }
  const navigations = () => calls.filter(([type]) => ['navigateTo', 'redirectTo', 'switchTab'].includes(type));
  const req = (part) => requests.filter((r) => r.url.includes(part));
  const ok = (r, data) => { if (!r || r.__done) return; r.__done = true; r.success({ statusCode: 200, data: { code: 0, data, msg: 'ok' } }); };
  const err = (r, statusCode, code, msg) => { if (!r || r.__done) return; r.__done = true; r.success({ statusCode, data: { code, msg } }); };
  const drain = (part, data) => { for (const r of req(part)) ok(r, data); };
  return {
    wx, calls, requests, storage, authPage, navigations, req, ok, err, drain,
    api: load('utils/api.js'),
    access: load('utils/access.js'),
    get loginCalls() { return loginCalls; },
  };
}

// ---- A) API-mock：激活页自身逻辑 ----

test('已激活账号进入激活页静默恢复会话并回到目标，无需输入授权码', async () => {
  for (const [redirect, expectedType, expectedUrl] of [
    ['', 'switchTab', '/pages/home/index'],
    ['/pages/ai-design/index', 'switchTab', '/pages/ai-design/index'],
    ['/pages/budget/input?source=home', 'redirectTo', '/pages/budget/input?source=home'],
  ]) {
    const env = runtime();
    env.api.login = async () => ({ accessToken: 'a', refreshToken: 'r' });
    env.api.getAccessGrant = async () => ({ status: 'ACTIVE' });
    let redeemed = 0;
    env.api.redeemAccessCode = async () => { redeemed++; return { status: 'ACTIVE' }; };
    const auth = env.authPage(redirect ? { redirect: encodeURIComponent(redirect) } : {});
    await auth._recovery;
    assert.equal(env.storage.v12Authorized, true, redirect);
    assert.equal(redeemed, 0, '已激活恢复不应触发兑换');
    const nav = env.navigations();
    assert.equal(nav.length, 1, redirect);
    assert.equal(nav[0][0], expectedType, redirect);
    assert.equal(nav[0][1].url, expectedUrl, redirect);
  }
});

test('账号未激活时恢复不导航，停留手动输码后可兑换成功', async () => {
  const env = runtime();
  env.api.login = async () => ({ accessToken: 'a', refreshToken: 'r' });
  env.api.getAccessGrant = async () => ({ status: 'NONE' });
  env.api.redeemAccessCode = async () => ({ status: 'ACTIVE' });
  const auth = env.authPage();
  await auth._recovery;
  assert.equal(env.navigations().length, 0);
  assert.equal(env.storage.v12Authorized, undefined);
  assert.equal(auth.data.accessReady, true);
  auth.onInput({ detail: { value: 'ZSZJ-TEST' } });
  await auth.verify();
  assert.equal(env.storage.v12Authorized, true);
  assert.equal(env.navigations().length, 1);
});

test('恢复失败（登录失败或授权查询网络/服务端错误）静默降级，不弹错误、不阻断手动兑换', async () => {
  const loginFail = runtime();
  loginFail.wx.login = (options) => options.fail?.({ errMsg: 'login denied' });
  loginFail.api.getAccessGrant = async () => ({ status: 'ACTIVE' });
  const a = loginFail.authPage();
  await a._recovery;
  assert.equal(loginFail.navigations().length, 0);
  assert.equal(loginFail.calls.filter(([t]) => t === 'toast').length, 0);
  assert.equal(a.data.accessReady, true);

  const grantFail = runtime();
  grantFail.api.login = async () => ({ accessToken: 'a', refreshToken: 'r' });
  grantFail.api.getAccessGrant = async () => { throw { code: 500, msg: 'boom' }; };
  const b = grantFail.authPage();
  await b._recovery;
  assert.equal(grantFail.navigations().length, 0);
  assert.equal(grantFail.calls.filter(([t]) => t === 'toast').length, 0);
  assert.equal(b.recovering, false);
});

test('已有有效令牌时恢复复用会话，不再消耗一次性 wx.login code', async () => {
  const env = runtime({ zs_access_token: 'existing-token' });
  env.api.getAccessGrant = async () => ({ status: 'ACTIVE' });
  const auth = env.authPage();
  await auth._recovery;
  assert.equal(env.loginCalls, 0, '令牌有效时不应再次 wx.login');
  assert.equal(env.navigations().length, 1);
});

test('过期令牌致身份失效时恢复强制重新 wx.login 救回已激活账号（区分身份过期 vs 网络错误）', async () => {
  const env = runtime({ zs_access_token: 'expired', zs_refresh_token: 'expired-ref' });
  let grantCalls = 0;
  env.api.getAccessGrant = async () => {
    grantCalls++;
    if (grantCalls === 1) throw { code: 401, msg: '会话无效或已过期' }; // 首次：身份过期
    return { status: 'ACTIVE' };                                        // 重登后：已激活
  };
  env.api.login = async () => ({ accessToken: 'fresh', refreshToken: 'fresh-ref' });
  const auth = env.authPage();
  await auth._recovery;
  assert.equal(env.loginCalls, 1, '身份过期应强制重新 wx.login 一次');
  assert.equal(grantCalls, 2, '重登后重查授权');
  assert.equal(env.storage.zs_access_token, 'fresh');
  assert.equal(env.storage.v12Authorized, true);
  assert.equal(env.navigations().length, 1);
});

test('恢复与手动兑换并发时共享同一次 wx.login（合并在途登录，不重复登录）', async () => {
  const env = runtime();
  let resolveLogin;
  env.api.login = () => new Promise((res) => { resolveLogin = res; }); // 登录挂起
  env.api.getAccessGrant = async () => ({ status: 'NONE' });
  env.api.redeemAccessCode = async () => ({ status: 'ACTIVE' });
  const auth = env.authPage();               // 恢复启动登录（挂起中）
  auth.onInput({ detail: { value: 'CODE' } });
  const verifying = auth.verify();           // 并发：共享在途登录，不再 wx.login
  await tick();
  assert.equal(env.loginCalls, 1, '并发下只应登录一次');
  resolveLogin({ accessToken: 'a', refreshToken: 'r' });
  await verifying; await auth._recovery;
  assert.equal(env.loginCalls, 1);
  assert.equal(env.storage.v12Authorized, true, '手动兑换成功激活');
  assert.equal(env.navigations().length, 1);
});

test('导航失败后可在本页重试激活：activated 仅在导航成功后置位', async () => {
  const env = runtime();
  env.api.login = async () => ({ accessToken: 'a', refreshToken: 'r' });
  env.api.getAccessGrant = async () => ({ status: 'NONE' });   // 不自动恢复，走手动
  env.api.redeemAccessCode = async () => ({ status: 'ACTIVE' });
  const auth = env.authPage({ redirect: encodeURIComponent('/pages/budget/input') });
  await auth._recovery;
  auth.onInput({ detail: { value: 'CODE' } });
  let failNav = true;
  env.wx.redirectTo = (o) => { env.calls.push(['redirectTo', o]); if (failNav) o.fail?.({ errMsg: 'nav fail' }); else o.success?.(); };
  await auth.verify();
  assert.equal(auth.activated, undefined, '导航失败不得置终态');
  assert.equal(auth._activating, false, '导航失败应释放在途以允许重试');
  assert.equal(env.storage.v12Authorized, true, '授权状态已写入');
  failNav = false;
  await auth.verify();   // 幂等兑换 ACTIVE → 重试导航成功
  assert.equal(auth.activated, true);
  assert.equal(env.calls.filter(([t]) => t === 'redirectTo').length, 2);
});

test('手动兑换导航失败提示“页面打开失败”，静默恢复导航失败保持静默（区分手动/恢复反馈）', async () => {
  // 手动路径：导航失败应弹提示（补回 access.navigate 原 navigationFailed 反馈，round-3 修复 P2）
  const manual = runtime();
  manual.api.login = async () => ({ accessToken: 'a', refreshToken: 'r' });
  manual.api.getAccessGrant = async () => ({ status: 'NONE' });   // 不自动恢复，走手动
  manual.api.redeemAccessCode = async () => ({ status: 'ACTIVE' });
  const m = manual.authPage({ redirect: encodeURIComponent('/pages/budget/input') });
  await m._recovery;
  m.onInput({ detail: { value: 'CODE' } });
  manual.wx.redirectTo = (o) => { manual.calls.push(['redirectTo', o]); o.fail?.({ errMsg: 'nav fail' }); };
  await m.verify();
  const mToasts = manual.calls.filter(([t]) => t === 'toast').map(([, o]) => o.title);
  assert.ok(mToasts.includes('激活成功'), '手动兑换成功仍先弹激活成功');
  assert.ok(mToasts.includes('页面打开失败，请重试'), '手动路径导航失败必须提示');
  assert.equal(m._activating, false, '导航失败释放在途');
  assert.equal(m.activated, undefined, '导航失败不置终态');

  // 静默恢复路径：导航失败不得弹任何提示（恢复静默契约）
  const silent = runtime();
  silent.api.login = async () => ({ accessToken: 'a', refreshToken: 'r' });
  silent.api.getAccessGrant = async () => ({ status: 'ACTIVE' });  // 触发静默恢复 activate
  silent.wx.redirectTo = (o) => { silent.calls.push(['redirectTo', o]); o.fail?.({ errMsg: 'nav fail' }); };
  const s = silent.authPage({ redirect: encodeURIComponent('/pages/budget/input') });
  await s._recovery;
  assert.equal(silent.calls.filter(([t]) => t === 'toast').length, 0, '静默恢复导航失败不得弹 toast');
  assert.equal(s.activated, undefined);
  assert.equal(s._activating, false);
});

test('静默恢复中授权写入失败不弹 toast（区别于手动兑换的失败提示）', async () => {
  const env = runtime();
  env.api.login = async () => ({ accessToken: 'a', refreshToken: 'r' });
  env.api.getAccessGrant = async () => ({ status: 'ACTIVE' });
  const originalSet = env.wx.setStorageSync;
  env.wx.setStorageSync = (k, v) => { if (k === 'v12Authorized') throw new Error('storage unavailable'); originalSet(k, v); };
  const auth = env.authPage();
  await auth._recovery;
  assert.equal(env.calls.filter(([t]) => t === 'toast').length, 0, '静默恢复不得弹 toast');
  assert.equal(env.navigations().length, 0);
  assert.equal(auth.activated, undefined, '写入失败不得置终态');
});

test('离页后迟到的恢复结果不再导航（生命周期失效）', async () => {
  const env = runtime();
  let resolveGrant;
  env.api.login = async () => ({ accessToken: 'a', refreshToken: 'r' });
  env.api.getAccessGrant = () => new Promise((res) => { resolveGrant = res; }); // 挂起
  const auth = env.authPage();
  await tick();                    // 登录完成、getAccessGrant 挂起中
  auth.continueBrowsing();         // 用户主动离开 → _leftPage=true
  resolveGrant({ status: 'ACTIVE' }); // 迟到返回 ACTIVE
  await auth._recovery;
  assert.equal(auth._leftPage, true);
  assert.equal(env.navigations().length, 0, '离页后不得导航到目标');
});

test('恢复与手动兑换先后成功时 activate 幂等，只导航一次', async () => {
  const env = runtime();
  env.api.login = async () => ({ accessToken: 'a', refreshToken: 'r' });
  env.api.getAccessGrant = async () => ({ status: 'ACTIVE' });
  env.api.redeemAccessCode = async () => ({ status: 'ACTIVE' });
  const auth = env.authPage();
  await auth._recovery;                    // 恢复成功 → 首次 activate
  assert.equal(env.navigations().length, 1);
  auth.onInput({ detail: { value: 'CODE' } });
  await auth.verify();                     // 手动兑换成功 → activate 被幂等守卫拦截
  assert.equal(env.navigations().length, 1, '不得二次导航');
});

// ---- B) 真实请求层：api→request.js 协同（会话代际 / 401 刷新） ----

test('并发恢复与手动兑换经真实请求层只登录一次，首次兑换不被会话代际冲突破坏', async () => {
  const env = runtime({}, { realRequest: true }); // 无令牌，走真实 api→request.js
  const auth = env.authPage();             // 恢复启动共享登录
  auth.onInput({ detail: { value: 'ZSZJ-CODE' } });
  const verifying = auth.verify();         // 并发手动兑换，共享登录在途
  await tick();
  assert.equal(env.req('/auth/wechat-login').length, 1, '并发下只发一次微信登录');
  assert.equal(env.loginCalls, 1);
  env.ok(env.req('/auth/wechat-login')[0], { accessToken: 'acc', refreshToken: 'ref', restricted: false });
  await tick();
  const grantReq = env.req('/access-grant')[0];
  const redeemReq = env.req('/access-code-redemptions')[0];
  assert.ok(grantReq && redeemReq, '登录后恢复查授权与手动兑换应并发发起');
  env.ok(grantReq, { status: 'NONE' });    // 恢复不自动放行
  env.ok(redeemReq, { status: 'ACTIVE' }); // 手动兑换成功
  await verifying; await auth._recovery;
  env.drain('/access-grant', { status: 'ACTIVE' }); // activate 内 refreshFromServer 对账，按兑换后真值应答
  await tick();
  assert.ok(!env.calls.some(([t, o]) => t === 'toast' && /身份已变化|SESSION_CHANGED/.test(o.title || '')),
    '不得出现会话代际冲突提示');
  assert.equal(env.storage.v12Authorized, true);
  assert.equal(env.navigations().length, 1);
});

test('过期令牌+刷新失效经真实请求层清会话后强制重登救回已激活账号', async () => {
  const env = runtime({ zs_access_token: 'expired', zs_refresh_token: 'expired-ref' }, { realRequest: true });
  const auth = env.authPage();             // 恢复复用过期令牌查授权 → 401 → 刷新 → 401 → 清会话 → 强制重登
  await tick();
  env.err(env.req('/access-grant')[0], 401, 401, 'expired');
  await tick();
  const refresh = env.req('/token-refresh')[0];
  assert.ok(refresh, '过期令牌应触发一次刷新');
  env.err(refresh, 401, 401, 'invalid refresh');
  await tick();
  assert.equal(env.storage.zs_access_token, undefined, '刷新失效后请求层应清会话');
  assert.equal(env.loginCalls, 1, '恢复应强制重新 wx.login');
  const login = env.req('/auth/wechat-login')[0];
  assert.ok(login);
  env.ok(login, { accessToken: 'fresh', refreshToken: 'fresh-ref', restricted: false });
  await tick();
  const grants = env.req('/access-grant');
  env.ok(grants[grants.length - 1], { status: 'ACTIVE' }); // 重登后重查授权
  await auth._recovery;
  env.drain('/access-grant', { status: 'ACTIVE' }); // activate 内 refreshFromServer 对账
  await tick();
  assert.equal(env.storage.zs_access_token, 'fresh');
  assert.equal(env.storage.v12Authorized, true);
  assert.equal(env.navigations().length, 1);
});

test('过期清会话后手动重试登录在途时，旧恢复响应迟到(SESSION_CHANGED)不得破坏重试（真实请求层）', async () => {
  const env = runtime({ zs_access_token: 'expired', zs_refresh_token: 'expired-ref' }, { realRequest: true });
  const auth = env.authPage();                 // 恢复：复用过期令牌查授权（挂起，由测试控制应答时机）
  await tick();
  const recoverGrant = env.req('/access-grant')[0];
  assert.ok(recoverGrant, '恢复应先发起授权查询');

  // 并发手动兑换：过期 → 刷新失败 → 请求层清会话（代际 bump），兑换失败提示
  auth.onInput({ detail: { value: 'ZSZJ-CODE' } });
  const firstVerify = auth.verify();
  await tick();
  env.err(env.req('/access-code-redemptions')[0], 401, 401, 'expired');
  await tick();
  env.err(env.req('/token-refresh')[0], 401, 401, 'invalid refresh');
  await firstVerify; await tick();
  assert.equal(env.storage.zs_access_token, undefined, '兑换链路刷新失败应清会话');

  // 用户重试兑换：无令牌 → 发起微信登录（挂起）
  const retryVerify = auth.verify();
  await tick();
  const retryLogin = env.req('/auth/wechat-login')[0];
  assert.ok(retryLogin, '重试应发起微信登录');

  // 旧恢复响应迟到：会话已被清（代际 bump）→ 请求层判 SESSION_CHANGED。
  //   修复前：恢复 catch 再次 clearTokens → 二次 bump → 作废在途重试登录 → 令牌终空、兑换/导航零次；
  //   修复后：SESSION_CHANGED 分支复用新会话（非 force）、绝不 clearTokens，重试登录照常落定。
  env.err(recoverGrant, 401, 401, 'stale');
  await tick();
  env.ok(retryLogin, { accessToken: 'fresh', refreshToken: 'fresh-ref', restricted: false });
  await tick();
  assert.equal(env.storage.zs_access_token, 'fresh', '在途重试登录不得被恢复的迟到错误作废');

  // 收敛：恢复复用新会话重查授权(NONE→不放行)，重试兑换 ACTIVE→激活导航一次
  env.drain('/access-grant', { status: 'NONE' });
  env.drain('/access-code-redemptions', { status: 'ACTIVE' });
  await retryVerify; await auth._recovery; await tick();
  env.drain('/access-grant', { status: 'ACTIVE' });   // activate 内 refreshFromServer 对账
  await tick();
  assert.equal(env.storage.v12Authorized, true);
  assert.equal(env.navigations().length, 1, '仅重试兑换激活导航一次');
  assert.equal(env.req('/access-code-redemptions').length, 2, '兑换共两次：首次失败 + 重试成功');
  assert.equal(env.req('/auth/wechat-login').length, 1, '仅重试发起一次微信登录');
  assert.ok(!env.calls.some(([t, o]) => t === 'toast' && /身份已变化|SESSION_CHANGED/.test(o.title || '')),
    '不得出现会话代际冲突提示');
});

test('离页发生在登录阶段时，恢复不再发起授权查询，避免请求层迟到 401 把用户拉回（真实请求层）', async () => {
  const env = runtime({}, { realRequest: true });   // 无令牌 → 恢复须先登录
  const auth = env.authPage();                      // 恢复：wx.login → POST /auth/wechat-login（挂起）
  await tick();
  const loginReq = env.req('/auth/wechat-login')[0];
  assert.ok(loginReq, '无令牌恢复应先发起微信登录');
  assert.equal(env.req('/access-grant').length, 0, '登录未落定前不应发起授权查询');

  auth.continueBrowsing();                          // 用户在登录阶段离页 → _leftPage=true, navigateBack
  env.ok(loginReq, { accessToken: 'acc', refreshToken: 'ref', restricted: false }); // 登录迟到成功
  await auth._recovery; await tick();

  // 关键：登录落定后复查生命周期 → 不再发起 getAccessGrant → 无在途授权查询可迟到 401 → 不被 showActivation 拉回。
  // navigateBack 已真实出栈 auth 页（getCurrentPages 不再返回 auth），故若 showActivation 触发必产生 redirectTo；
  // 下面「无 redirect」断言因此独立证明离页后请求层未把用户拉回（而非因当前仍在 auth 页自行短路）。
  assert.ok(env.calls.some(([t]) => t === 'navigateBack'), '继续浏览应触发 navigateBack（真实卸载当前页）');
  assert.equal(env.req('/access-grant').length, 0, '离页后恢复不得发起授权查询');
  assert.equal(auth._leftPage, true);
  assert.equal(env.calls.filter(([t]) => t === 'redirectTo').length, 0, '离页后不得被重定向拉回激活页');
});

test('授权查询已在途时离页，迟到 401 只清会话不把用户拉回激活页（P2#1 闭环，真实请求层）', async () => {
  // 承 T13-07 P2#1：上一个用例守住「登录阶段离页→不发 grant」；本例补其补集——grant 已在途后用户才离页，
  // 此时迟到的 401 仍会走完请求层清会话，但 silent 必须抑制 showActivation，不把已 navigateBack 出栈的用户重定向拉回。
  const env = runtime({ zs_access_token: 'valid', zs_refresh_token: 'valid-ref' }, { realRequest: true });
  const auth = env.authPage();               // 令牌有效 → 恢复复用会话直接发起授权查询（不 wx.login）
  await tick();
  const grantReq = env.req('/access-grant')[0];
  assert.ok(grantReq, '恢复应发起在途授权查询');

  auth.continueBrowsing();                   // grant 在途时用户主动离页 → _leftPage=true, navigateBack 真实出栈 auth 页
  assert.ok(env.calls.some(([t]) => t === 'navigateBack'), '继续浏览应触发 navigateBack（真实卸载当前页）');

  env.err(grantReq, 401, 401, 'late expiry'); // 离页后授权查询迟到 401
  await tick();
  const refresh = env.req('/token-refresh')[0];
  assert.ok(refresh, '迟到 401 应触发一次刷新');
  env.err(refresh, 401, 401, 'invalid refresh'); // 刷新也失效 → 请求层清会话
  await auth._recovery; await tick();

  // 关键：navigateBack 已出栈 auth 页（getCurrentPages 顶页非 auth），若 silent 失效则 showActivation 必产生 redirectTo；
  //   下面「无 redirect」断言因此独立证明离页后迟到 401 未把用户拉回（而非因仍在 auth 页自行短路）。
  assert.equal(env.storage.zs_access_token, undefined, 'silent 仍清失效会话（状态同步）');
  assert.equal(env.storage.v12Authorized, false, 'silent 仍同步授权快照');
  assert.equal(env.loginCalls, 0, '令牌有效不登录；离页后迟到 401 也不触发强制重登（_leftPage 守卫）');
  assert.equal(env.req('/access-grant').length, 1, '离页后不再重发授权查询');
  assert.equal(env.calls.filter(([t]) => t === 'redirectTo').length, 0, '离页后迟到 401 不得把用户重定向拉回激活页');
});

test('后台对账 refreshFromServer 以 silent 发起，迟到 401 只清会话不把用户拉回激活页（P2#1 承 app.onShow 场景）', async () => {
  // app.onShow 冷启动/回前台触发的 refreshFromServer 是 P2#1 最高频后台触发点：用户此刻多在首页/功能页而非激活页，
  //   在途授权对账的迟到 401 若走非 silent 会 showActivation 把用户从当前页强制拉回激活页。此例锁定 access.js 确实传 silent。
  const env = runtime({ zs_access_token: 'valid', zs_refresh_token: 'valid-ref' }, { realRequest: true });
  // 不创建 auth 页：pages 为空，等价于 getCurrentPages 顶页非 auth（用户在其它页时 app 回前台）——非 silent 必触发 redirectTo。
  const refreshing = env.access.refreshFromServer();
  await tick();
  const grantReq = env.req('/access-grant')[0];
  assert.ok(grantReq, 'refreshFromServer 应发起后台授权对账');
  env.err(grantReq, 401, 401, 'late expiry');
  await tick();
  const refresh = env.req('/token-refresh')[0];
  assert.ok(refresh, '迟到 401 应触发一次刷新');
  env.err(refresh, 401, 401, 'invalid refresh');
  await refreshing; await tick();
  assert.equal(env.storage.zs_access_token, undefined, 'silent 仍清失效会话（状态同步）');
  assert.equal(env.storage.v12Authorized, false, 'silent 仍同步授权快照');
  assert.equal(env.calls.filter(([t]) => t === 'redirectTo').length, 0, '后台对账迟到 401 不得把用户重定向拉回激活页');
});

// ---- C) 加载态视觉契约 ----

test('加载态按钮契约：处理中文案切换为“激活中…”且禁用点击', () => {
  const wxml = fs.readFileSync(path.join(root, 'pages/auth/index.wxml'), 'utf8');
  assert.match(wxml, /v12-primary-button \{\{loading \? 'is-loading' : ''\}\}/);
  assert.match(wxml, /\{\{loading \? '激活中…' : '激活并继续'\}\}/);
  const scss = fs.readFileSync(path.join(root, 'pages/auth/index.scss'), 'utf8');
  assert.match(scss, /\.v12-primary-button\.is-loading \{[^}]*pointer-events: none;/);
});
