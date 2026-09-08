const fs = require('fs');
const path = require('path');
const assert = require('assert');

const root = path.resolve(__dirname, '..');

function read(relativePath) {
  return fs.readFileSync(path.join(root, relativePath), 'utf8');
}

function exists(relativePath) {
  return fs.existsSync(path.join(root, relativePath));
}

function walk(relativeDirectory, extension) {
  const directory = path.join(root, relativeDirectory);
  if (!fs.existsSync(directory)) return [];
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const relativePath = path.join(relativeDirectory, entry.name);
    return entry.isDirectory()
      ? walk(relativePath, extension)
      : relativePath.endsWith(extension) ? [relativePath] : [];
  });
}

const projectConfig = JSON.parse(read('project.config.json'));
assert.strictEqual(projectConfig.miniprogramRoot, 'miniprogram/', '微信开发者工具应把前端程序作为独立原生小程序打开');

const appConfig = JSON.parse(read('miniprogram/app.json'));
const entryPages = [
  'pages/home/index',
  'pages/library/index',
  'pages/ai-design/index',
  'pages/profile/index',
];
const flowPages = [
  'pages/auth/index',
  'pages/library/detail',
  'pages/ai-design/generating',
  'pages/ai-design/plane-select',
  'pages/ai-design/elevation-setup',
  'pages/ai-design/elevation-select',
  'pages/ai-design/result',
  'pages/ai-design/publish',
  'pages/budget/input',
  'pages/budget/result',
  'pages/wallet/recharge',
  'pages/payment/success',
  'pages/messagecenter/messagecenter',
];
const expectedPages = [...entryPages, ...flowPages];

assert.deepStrictEqual(appConfig.pages, expectedPages, 'V1.2 应只注册 18 张效果图对应的页面状态和消息辅助页');
assert.deepStrictEqual(
  appConfig.tabBar.list.map(({ pagePath, text }) => [pagePath, text]),
  [
    ['pages/home/index', '首页'],
    ['pages/library/index', '户型库'],
    ['pages/ai-design/index', 'AI设计'],
    ['pages/profile/index', '我的'],
  ],
  '底部导航应与 V1.2 效果图保持一致',
);

for (const pagePath of expectedPages) {
  for (const extension of ['js', 'json', 'wxml', 'scss']) {
    assert.ok(exists(`miniprogram/${pagePath}.${extension}`), `${pagePath}.${extension} 应存在于原生页面结构中`);
  }
  assert.match(read(`miniprogram/${pagePath}.wxml`), /<v12-navbar/, `${pagePath} 应使用 V1.2 统一导航栏`);
}

for (const pagePath of entryPages) {
  assert.doesNotMatch(read(`miniprogram/${pagePath}.wxml`), /show-back/, `${pagePath} 是入口页，不应显示返回按钮`);
}
for (const pagePath of flowPages) {
  assert.match(read(`miniprogram/${pagePath}.wxml`), /<v12-navbar[^>]*show-back/, `${pagePath} 应提供返回按钮`);
}

const registeredPages = new Set(expectedPages.map((pagePath) => `/${pagePath}`));
for (const scriptPath of walk('miniprogram/pages', '.js').concat(walk('miniprogram/components', '.js'))) {
  const script = read(scriptPath);
  for (const match of script.matchAll(/url:\s*['"`]([^'"`]+)['"`]/g)) {
    const route = match[1].split('?')[0];
    if (route.startsWith('/pages/')) {
      assert.ok(registeredPages.has(route), `${scriptPath} 不应导航到 V1.2 页面集之外：${route}`);
    }
  }
}

assert.ok(!exists('miniprogram/pages/ai-house'), 'V1.2 不应保留旧 AI户型 页面目录');
assert.ok(!exists('miniprogram/pages/member'), 'V1.2 已取消会员中心，不应保留旧页面目录');

const registeredSource = expectedPages.flatMap((pagePath) => [
  read(`miniprogram/${pagePath}.js`),
  read(`miniprogram/${pagePath}.wxml`),
]).join('\n');
assert.doesNotMatch(registeredSource, /AI户型|参考户型|自由生成|会员中心|开通会员|用户注册/, 'V1.2 页面不得出现旧术语或已取消功能');

for (const copy of [
  '公司案例',
  'AI案例',
  '面积从小到大',
  '户型设计',
  '自主设计',
  '生成平面方案',
  '选定并生成立面',
  '生成立面方案',
  '完成方案',
  '参数校核',
  '激活并继续',
  '基础设计点',
  '赠送设计点',
  '到账设计点',
]) {
  assert.match(registeredSource, new RegExp(copy), `V1.2 关键文案“${copy}”应出现在页面代码中`);
}

assert.ok(exists('miniprogram/components/v12-navbar/index.js'), 'V1.2 统一导航栏应打包在前端程序中');
assert.ok(exists('miniprogram/components/tdesign-miniprogram/icon/icon.json'), '图标组件配置必须随项目本地交付');
assert.ok(exists('miniprogram/components/tdesign-miniprogram/icon/icon.wxss'), '图标字体样式必须随项目本地交付');
assert.ok(
  exists('miniprogram/components/tdesign-miniprogram/miniprogram_npm/tslib/index.js'),
  '本地图标组件应包含 tslib 运行时，避免导入微信开发者工具后图标丢失',
);
assert.doesNotMatch(
  read('miniprogram/components/tdesign-miniprogram/icon/icon.wxss'),
  /https?:\/\//,
  '图标字体不得依赖远程地址，导入微信开发者工具后应可离线显示',
);

const iconStyles = read('miniprogram/components/tdesign-miniprogram/icon/icon.wxss');
for (const pagePath of expectedPages) {
  const markup = read(`miniprogram/${pagePath}.wxml`);
  for (const match of markup.matchAll(/src="(\/assets\/v12\/[^"]+)"/g)) {
    assert.ok(exists(`miniprogram${match[1]}`), `${pagePath} 引用的素材 ${match[1]} 必须随前端工程交付`);
  }
  for (const match of markup.matchAll(/<t-icon[^>]*\sname="([a-z0-9-]+)"/g)) {
    const iconName = match[1];
    assert.match(
      iconStyles,
      new RegExp(`\\.t-icon-${iconName}:before`),
      `${pagePath} 使用的图标 ${iconName} 必须存在于本地图标字库中`,
    );
  }
}

const customTabScript = read('miniprogram/custom-tab-bar/index.js');
const customTabData = customTabScript.split('Component({')[0];
for (const match of customTabData.matchAll(/(?:activeIcon|icon):\s*'([a-z0-9-]+)'/g)) {
  assert.match(
    iconStyles,
    new RegExp(`\\.t-icon-${match[1]}:before`),
    `底部导航图标 ${match[1]} 必须存在于本地图标字库中`,
  );
}
assert.match(customTabData, /text: 'AI设计',[^\n]*glyph: '✦'/, 'AI 设计底部导航应使用效果图中的四向闪光字形');
assert.match(
  read('miniprogram/custom-tab-bar/index.wxml'),
  /wx:if="{{item\.glyph}}"[^>]*class="tabbar-glyph"/,
  '底部导航应直接渲染原生闪光字形，避免依赖不存在的图标资源',
);

const homeMarkup = read('miniprogram/pages/home/index.wxml');
assert.match(homeMarkup, /class="hero-art"/, '首页主视觉应使用拆分后的房屋素材并保留文字叠加结构');

const detailMarkup = read('miniprogram/pages/library/detail.wxml');
assert.match(detailMarkup, /src="\/assets\/v12\/villa-detail\.png"/, '户型详情应使用从 V1.2 详情效果图拆分的精确头图');
for (const copy of ['立面效果', '一层平面', '二层平面', '设计说明']) {
  assert.match(detailMarkup, new RegExp(copy), `户型详情应包含效果图中的“${copy}”结构`);
}
assert.doesNotMatch(detailMarkup, /class="stats"/, '户型详情不应保留效果图中不存在的四列统计模块');

const aiDesignMarkup = read('miniprogram/pages/ai-design/index.wxml');
assert.match(aiDesignMarkup, /<v12-navbar show-notice\s*\/>/, 'AI 设计页品牌栏不应把页面标题挤到微信胶囊同一行');
assert.match(aiDesignMarkup, /class="ai-page-title">AI设计<\/view>/, 'AI 设计页标题应与消息铃铛位于品牌栏下一行');
const aiDesignStyles = read('miniprogram/pages/ai-design/index.scss');
assert.match(aiDesignStyles, /@media \(max-width: 350px\)[\s\S]*\.step-intro[^}]*flex-wrap:\s*wrap/, '小屏设备上的 AI 步骤说明应允许换行');
assert.match(aiDesignStyles, /@media \(max-width: 350px\)[\s\S]*\.points[^}]*flex-wrap:\s*wrap/, '小屏设备上的设计点信息应允许换行');

const theme = `${read('miniprogram/styles/v12-tokens.scss')}\n${read('miniprogram/styles/v12-theme.scss')}`;
for (const token of ['#f7f4ee', '#fffdf8', '#6a3b1b', '#7b5532', '#c8a679', '#2f2925', '#786858', '#e8ded1']) {
  assert.match(theme.toLowerCase(), new RegExp(token), `V1.2 主题应包含色值 ${token}`);
}

console.log('众墅之家 V1.2 原生小程序结构与离线图标检查通过');
