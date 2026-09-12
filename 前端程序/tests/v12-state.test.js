const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../miniprogram');
const compDir = path.join(root, 'components/v12-state');

// 组件定义加载：vm 内注入 Component 全局，捕获定义对象（与页面 harness 同思路）。
function loadComponent() {
  let definition;
  vm.runInNewContext(fs.readFileSync(path.join(compDir, 'index.js'), 'utf8'), {
    Component(value) { definition = value; },
  });
  return definition;
}

test('v12-state 声明为组件，并暴露 status/text/hint/icon/actionText/card 六个属性与三态默认文案', () => {
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(compDir, 'index.json'), 'utf8')), { component: true });
  const def = loadComponent();
  for (const key of ['status', 'text', 'hint', 'icon', 'actionText', 'card']) {
    assert.ok(def.properties[key], `缺少属性 ${key}`);
  }
  assert.equal(def.properties.status.value, 'empty');
  // type 是 vm realm 的构造器，与测试 realm 的 Boolean 非引用相等，按构造器名比较
  assert.equal(def.properties.card.type.name, 'Boolean');
  assert.equal(def.properties.card.value, true);
  // defaults 对象在 vm realm 创建，用 JSON 往返在当前 realm 重建后再比较，避免原型不等
  assert.deepEqual(JSON.parse(JSON.stringify(def.data.defaults)), { loading: '正在加载…', empty: '暂无内容', error: '加载失败，请重试' });
});

test('点击动作按钮只派发 action 事件，业务处理留在页面（组件无副作用）', () => {
  const def = loadComponent();
  const events = [];
  def.methods.onAction.call({ triggerEvent: name => events.push(name) });
  assert.deepEqual(events, ['action']);
});

test('组件模板按 status 回落文案、按需渲染 icon/hint/action，并按 card 切换卡片类', () => {
  const wxml = fs.readFileSync(path.join(compDir, 'index.wxml'), 'utf8');
  assert.match(wxml, /\{\{text \|\| defaults\[status\]\}\}/);
  assert.match(wxml, /<t-icon wx:if="\{\{icon\}\}"/);
  assert.match(wxml, /wx:if="\{\{hint\}\}"/);
  assert.match(wxml, /wx:if="\{\{actionText\}\}"/);
  assert.match(wxml, /bindtap="onAction"/);
  assert.match(wxml, /card \? 'v12-state--card'/);
});

test('records/messagecenter/library 三页已接入 v12-state 且在 json 注册组件', () => {
  const pages = [
    ['pages/profile/records.wxml', 'pages/profile/records.json'],
    ['pages/messagecenter/messagecenter.wxml', 'pages/messagecenter/messagecenter.json'],
    ['pages/library/index.wxml', 'pages/library/index.json'],
  ];
  for (const [wxmlRel, jsonRel] of pages) {
    const wxml = fs.readFileSync(path.join(root, wxmlRel), 'utf8');
    const json = JSON.parse(fs.readFileSync(path.join(root, jsonRel), 'utf8'));
    assert.match(wxml, /<v12-state[\s>]/, `${wxmlRel} 应使用 <v12-state>`);
    assert.equal(json.usingComponents['v12-state'], '/components/v12-state/index', `${jsonRel} 应注册 v12-state`);
  }
});

test('迁移后三页不再残留内联空态类（record-empty / message-empty / empty-tip）', () => {
  const records = fs.readFileSync(path.join(root, 'pages/profile/records.wxml'), 'utf8');
  const message = fs.readFileSync(path.join(root, 'pages/messagecenter/messagecenter.wxml'), 'utf8');
  const library = fs.readFileSync(path.join(root, 'pages/library/index.wxml'), 'utf8');
  assert.ok(!records.includes('record-empty'), 'records.wxml 不应再用 record-empty');
  assert.ok(!message.includes('message-empty'), 'messagecenter.wxml 不应再用 message-empty');
  assert.ok(!library.includes('empty-tip'), 'library/index.wxml 不应再用 empty-tip');
});

// 回归守卫：detail 页 record.wxml 仍用 .record-empty，且 record.scss @import records.scss 复用其规则。
// 在 detail 页迁移前，records.scss 的 .record-empty 不可删——否则会连带击穿 detail 页空态样式。
test('records.scss 保留 .record-empty（detail 页经 record.scss @import 复用，未迁移前不可删）', () => {
  const recordScss = fs.readFileSync(path.join(root, 'pages/profile/record.scss'), 'utf8');
  assert.match(recordScss, /@import '\.\/records\.scss'/);
  const recordsScss = fs.readFileSync(path.join(root, 'pages/profile/records.scss'), 'utf8');
  assert.match(recordsScss, /\.record-empty\s*\{/);
  const recordWxml = fs.readFileSync(path.join(root, 'pages/profile/record.wxml'), 'utf8');
  assert.match(recordWxml, /record-empty/);
});
