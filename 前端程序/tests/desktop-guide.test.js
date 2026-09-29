const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../miniprogram');
const compDir = path.join(root, 'components/v12-desktop-guide');

// 组件定义加载：vm 内注入 Component 与可变 wx stub（attached/methods 的自由变量解析到 vm context）
function load(wxStub) {
  let definition;
  vm.runInNewContext(fs.readFileSync(path.join(compDir, 'index.js'), 'utf8'), {
    Component(value) { definition = value; },
    wx: wxStub
  });
  return definition;
}

function instance(definition) {
  return { data: JSON.parse(JSON.stringify(definition.data)), setData(patch) { Object.assign(this.data, patch); } };
}

function wxWith({ platform = 'android', storage = {} } = {}) {
  return {
    getDeviceInfo: () => ({ platform }),
    getStorageSync: key => storage[key],
    setStorageSync: (key, value) => { storage[key] = value; }
  };
}

test('desktop-guide 声明为组件并注册 t-icon；方法与生命周期齐全', () => {
  const json = JSON.parse(fs.readFileSync(path.join(compDir, 'index.json'), 'utf8'));
  assert.equal(json.component, true);
  assert.equal(json.usingComponents['t-icon'], '/components/tdesign-miniprogram/icon/icon');
  const def = load(wxWith());
  assert.ok(def.lifetimes && def.lifetimes.attached, '缺少 attached 生命周期');
  for (const method of ['showSteps', 'closeSheet', 'dismiss', 'keepSheet']) {
    assert.equal(typeof def.methods[method], 'function', `缺少方法 ${method}`);
  }
});

test('Android 平台展示桌面引导步骤，且默认未关闭时可见', () => {
  const def = load(wxWith({ platform: 'android', storage: {} }));
  const inst = instance(def);
  def.lifetimes.attached.call(inst);
  assert.equal(inst.data.platform, 'android');
  assert.equal(inst.data.closed, false, '未写过关闭标记时应展示引导条');
  assert.match(inst.data.steps[0].title, /向下拉动/);
  assert.match(inst.data.steps[1].detail, /添加到桌面/);
});

test('iOS 平台切换为「我的小程序」话术，不承诺放桌面', () => {
  const def = load(wxWith({ platform: 'ios', storage: {} }));
  const inst = instance(def);
  def.lifetimes.attached.call(inst);
  assert.equal(inst.data.platform, 'ios');
  assert.match(inst.data.steps[0].detail, /我的小程序/);
  const wxml = fs.readFileSync(path.join(compDir, 'index.wxml'), 'utf8');
  assert.match(wxml, /iOS 系统不允许小程序直接放到桌面/);
  assert.match(wxml, /platform === 'ios' \? '把「众墅之家」加入我的小程序' : '把「众墅之家」添加到手机桌面'/);
});

test('写过关闭标记后不再展示；dismiss 落 storage 并收起', () => {
  const storage = { zs_desktop_guide_closed_v1: true };
  const def = load(wxWith({ platform: 'android', storage }));
  const closed = instance(def);
  def.lifetimes.attached.call(closed);
  assert.equal(closed.data.closed, true, '关闭标记存在时不展示');

  const fresh = { zs_desktop_guide_closed_v1: false };
  const def2 = load(wxWith({ platform: 'android', storage: fresh }));
  const inst = instance(def2);
  def2.lifetimes.attached.call(inst);
  def2.methods.showSteps.call(inst);
  assert.equal(inst.data.expanded, true);
  def2.methods.dismiss.call(inst);
  assert.equal(inst.data.closed, true);
  assert.equal(inst.data.expanded, false);
  assert.equal(fresh['zs_desktop_guide_closed_v1'], true, 'dismiss 必须持久化关闭标记');
});

test('home 与 profile 两页已接入 v12-desktop-guide 且在 json 注册', () => {
  const pages = [
    ['pages/home/index.wxml', 'pages/home/index.json'],
    ['pages/profile/index.wxml', 'pages/profile/index.json']
  ];
  for (const [wxmlRel, jsonRel] of pages) {
    const wxml = fs.readFileSync(path.join(root, wxmlRel), 'utf8');
    const json = JSON.parse(fs.readFileSync(path.join(root, jsonRel), 'utf8'));
    assert.match(wxml, /<v12-desktop-guide\s*\/>/, `${wxmlRel} 应使用 <v12-desktop-guide />`);
    assert.equal(json.usingComponents['v12-desktop-guide'], '/components/v12-desktop-guide/index', `${jsonRel} 应注册组件`);
  }
});
