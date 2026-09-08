const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../miniprogram');
const config = require('../miniprogram/app.json');

function runtime(authorized = false) {
  const calls = [];
  const storage = { v12Authorized: authorized };
  const pages = [];
  const modules = new Map();
  let definition;
  let component;
  let app;
  const wx = {
    getStorageSync(key) { return storage[key]; },
    setStorageSync(key, value) { storage[key] = value; },
    showToast(options) { calls.push(['toast', options]); },
    showModal(options) { calls.push(['modal', options]); },
    navigateTo(options) { calls.push(['navigateTo', options]); options.success?.(); },
    redirectTo(options) { calls.push(['redirectTo', options]); options.success?.(); },
    switchTab(options) { calls.push(['switchTab', options]); options.success?.(); },
    navigateBack(options = {}) { calls.push(['navigateBack', options]); options.success?.(); },
  };
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
      Component(value) { component = value; },
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
  function page(route, query = {}) {
    load(`${route}.js`);
    const instance = {
      ...definition, route, options: query,
      data: JSON.parse(JSON.stringify(definition.data || {})),
      setData(value) { Object.assign(this.data, value); },
    };
    pages.push(instance);
    instance.onLoad?.(query);
    instance.onShow?.();
    return instance;
  }
  return { wx, calls, storage, pages, load, page, get app() { return app; },
    component(filename = 'custom-tab-bar/index.js') { load(filename); return component; } };
}

test('正常启动以首页为入口，不主动弹出激活窗口', () => {
  assert.equal(config.pages[0], 'pages/home/index');
  for (const authorized of [false, true, 'expired']) {
    const env = runtime(authorized);
    env.app.onShow?.({ path: 'pages/home/index' });
    env.page('pages/home/index');
    assert.equal(env.calls.length, 0);
  }
});

test('首页功能入口先提示，取消保留首页，确认才打开二级激活页', () => {
  for (const method of ['openAi', 'openLibrary', 'openDetail', 'openBudget']) {
    const env = runtime();
    const home = env.page('pages/home/index');
    home[method]();
    assert.equal(env.calls[0][0], 'modal', method);
    const modal = env.calls[0][1];
    assert.equal(modal.cancelText, '继续浏览');
    assert.equal(modal.confirmText, '去激活');
    modal.success({ confirm: false, cancel: true });
    modal.complete?.();
    assert.equal(env.calls.length, 1);
    home[method]();
    env.calls[1][1].success({ confirm: true });
    assert.equal(env.calls[2][0], 'navigateTo');
    assert.match(env.calls[2][1].url, /^\/pages\/auth\/index\?/);
  }
});

test('连续点击只显示一个激活弹窗', () => {
  const env = runtime();
  const home = env.page('pages/home/index');
  home.openAi();
  home.openBudget();
  assert.equal(env.calls.filter(([type]) => type === 'modal').length, 1);
});

test('所有底部入口遵守相同门禁，我的基础页可打开', () => {
  for (const index of [1, 2, 3]) {
    const env = runtime();
    const definition = env.component();
    definition.methods.switchTab.call({ data: definition.data }, { currentTarget: { dataset: { index } } });
    assert.equal(env.calls[0][0], index === 3 ? 'switchTab' : 'modal');
  }
});

test('首页消息入口同样需要激活', () => {
  const env = runtime();
  const definition = env.component('components/v12-navbar/index.js');
  definition.methods.openMessages();
  assert.equal(env.calls[0][0], 'modal');
});

test('已激活用户点击直接进入原功能', () => {
  const env = runtime(true);
  env.page('pages/home/index').openAi();
  assert.equal(env.calls[0][0], 'switchTab');
  assert.equal(env.calls[0][1].url, '/pages/ai-design/index');
});

test('非布尔有效状态和读取失败不能放行业务，但首页仍能显示', () => {
  for (const status of [undefined, 'true', 'expired', 'disabled', false]) {
    const env = runtime(status);
    env.page('pages/home/index').openAi();
    assert.equal(env.calls[0][0], 'modal');
  }
  const env = runtime();
  env.wx.getStorageSync = () => { throw new Error('storage unavailable'); };
  env.page('pages/home/index').openAi();
  assert.equal(env.calls[0][0], 'modal');
});

test('未激活直达任何业务页时不展示页面，也不初始化业务', () => {
  for (const route of config.pages.filter((route) => !['pages/home/index', 'pages/profile/index', 'pages/auth/index'].includes(route))) {
    const env = runtime();
    const page = env.page(route, { stage: 'elevation', count: '4' });
    assert.equal(page.data.accessReady, false, route);
    assert.ok(env.calls.some(([type, options]) => type === 'switchTab' && options.url === '/pages/home/index'), route);
    assert.equal(env.calls.filter(([type]) => type === 'modal').length, 0, route);
    assert.match(fs.readFileSync(path.join(root, `${route}.wxml`), 'utf8'), /^<view[^>]*wx:if="{{accessReady}}"/, route);
    if (route.endsWith('/generating')) assert.equal(page.data.stage, 'plane');
  }
});

test('授权失效后页面隐藏，点击不能继续生成或支付', () => {
  for (const [route, action] of [['pages/ai-design/index', 'generate'], ['pages/wallet/recharge', 'pay']]) {
    const env = runtime(true);
    env.page('pages/home/index');
    const page = env.page(route);
    assert.equal(page.data.accessReady, true);
    env.storage.v12Authorized = false;
    page[action]();
    assert.equal(page.data.accessReady, false);
    assert.ok(!env.calls.some(([type]) => ['navigateTo', 'redirectTo'].includes(type)));
    assert.ok(env.calls.some(([type]) => type === 'modal'));
  }
});

test('受限页中的导航栏与底部导航也清除失效内容并引导激活', () => {
  for (const entry of ['message', 'tab']) {
    const env = runtime(true);
    env.page('pages/home/index');
    const page = env.page('pages/ai-design/index');
    env.storage.v12Authorized = false;
    if (entry === 'message') env.component('components/v12-navbar/index.js').methods.openMessages();
    else {
      const tab = env.component();
      tab.methods.switchTab.call({ data: { ...tab.data, current: 2 } }, { currentTarget: { dataset: { index: 1 } } });
    }
    assert.equal(page.data.accessReady, false);
    assert.equal(env.calls[0][0], 'switchTab');
    assert.equal(env.calls[0][1].url, '/pages/home/index');
    assert.equal(env.calls[1][0], 'modal');
    env.calls[1][1].success({ cancel: true });
    assert.equal(env.calls.length, 2);
  }
});

test('激活往返只解码外层导航参数，原目标的编码参数保持原样', () => {
  const target = '/pages/budget/input?source=home%26card&title=%E6%88%B7%E5%9E%8B';
  const env = runtime();
  env.page('pages/home/index');
  env.load('utils/access.js').openFeature(target);
  env.calls[0][1].success({ confirm: true });
  const encoded = env.calls[1][1].url.split('?redirect=')[1];
  assert.equal(decodeURIComponent(encoded), target);
  const auth = env.page('pages/auth/index', { redirect: encoded });
  auth.onInput({ detail: { value: 'DEMO-ONLY' } });
  auth.verify();
  assert.equal(env.calls.at(-1)[1].url, target);
});

test('激活页默认空码，演示激活成功按白名单回到原功能并保留查询参数', () => {
  for (const target of ['/pages/ai-design/index', '/pages/budget/input?source=home']) {
    const env = runtime();
    env.page('pages/home/index');
    const auth = env.page('pages/auth/index', { redirect: encodeURIComponent(target) });
    assert.equal(auth.data.code, '');
    auth.verify();
    assert.equal(env.storage.v12Authorized, false);
    auth.onInput({ detail: { value: 'DEMO-ONLY' } });
    auth.verify();
    assert.equal(env.storage.v12Authorized, true);
    assert.equal(env.calls.at(-1)[1].url, target);
    assert.equal(env.calls.at(-1)[0], target.includes('ai-design') ? 'switchTab' : 'redirectTo');
  }
});

test('激活写入失败留在授权页，非法回跳回到首页', () => {
  const broken = runtime();
  broken.page('pages/home/index');
  const auth = broken.page('pages/auth/index');
  auth.onInput({ detail: { value: 'DEMO-ONLY' } });
  broken.wx.setStorageSync = () => { throw new Error('storage unavailable'); };
  auth.verify();
  assert.ok(!broken.calls.some(([type]) => ['navigateTo', 'redirectTo', 'switchTab'].includes(type)));
  assert.equal(broken.calls.at(-1)[0], 'toast');
  for (const target of ['https://example.com', '/pages/auth/index', '/pages/unknown/index', '%E0%A4%A']) {
    const env = runtime();
    env.page('pages/home/index');
    const page = env.page('pages/auth/index', { redirect: target });
    page.onInput({ detail: { value: 'DEMO-ONLY' } });
    page.verify();
    assert.equal(env.calls.at(-1)[1].url, '/pages/home/index');
  }
});

test('未激活的我的基础页不虚构身份和余额，激活状态切换可即时刷新', () => {
  const env = runtime();
  env.page('pages/home/index');
  const profile = env.page('pages/profile/index');
  assert.equal(profile.data.authorized, false);
  assert.equal(env.calls.length, 0);
  profile.openRecharge();
  assert.equal(env.calls[0][0], 'modal');
  env.storage.v12Authorized = true;
  profile.onShow();
  assert.equal(profile.data.authorized, true);
});

test('冷启动直达功能或授权页也先落首页，普通前后台切换不打断操作', () => {
  for (const authorized of [false, true]) {
    const env = runtime(authorized);
    env.app.onShow?.({ path: 'pages/auth/index' });
    assert.equal(env.calls[0]?.[1].url, '/pages/home/index');
    env.calls.length = 0;
    env.page('pages/home/index');
    env.app.onShow?.({ path: 'pages/ai-design/index' });
    assert.equal(env.calls.length, 0);
  }
});
