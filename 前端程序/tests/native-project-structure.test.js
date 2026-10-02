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

// 页面、导航栏和自定义 tabBar 交付的是 SCSS，不是预编译 WXSS。
// 微信工具也可能以 miniprogram/ 为导入根；检查实际存在的两种配置及本机覆盖。
for (const configPath of ['project.config.json', 'miniprogram/project.config.json']) {
  if (!exists(configPath)) continue;
  const config = JSON.parse(read(configPath));
  const privatePath = configPath.replace('project.config.json', 'project.private.config.json');
  const privateConfig = exists(privatePath) ? JSON.parse(read(privatePath)) : {};
  const settings = { ...config.setting, ...privateConfig.setting };
  assert.ok(
    Array.isArray(settings.useCompilerPlugins) && settings.useCompilerPlugins.includes('sass'),
    `${configPath} 必须启用 sass 编译，否则 app/页面/导航/tabBar 的 SCSS 不会成为可用样式`,
  );
}

// T13-08：发布工程 AppID 统一——外层（DevTools 推荐发布根）与内层（本机单独导入）必须登记同一正式 AppID，
//   且不得回退到 touristappid 占位符：占位符无法通过微信审核发布，历史上外层曾遗留 touristappid 造成内外层不一致（B02）。
const RELEASE_APPID = 'wxfa0a79d4e68cdb80';
for (const configPath of ['project.config.json', 'miniprogram/project.config.json']) {
  const config = JSON.parse(read(configPath));
  assert.strictEqual(config.appid, RELEASE_APPID, `${configPath} 必须登记众墅之家正式 AppID，不得为 touristappid 占位符或与内层不一致`);
}

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
  'pages/ai-design/elevation-setup',
  'pages/ai-design/result',
  'pages/ai-design/publish',
  'pages/budget/input',
  'pages/budget/parameters',
  'pages/budget/body',
  'pages/budget/exterior',
  'pages/budget/result',
  'pages/budget/body-detail',
  'pages/budget/exterior-detail',
  'pages/budget/history',
  'pages/budget/prices',
  'pages/wallet/recharge',
  'pages/payment/success',
  'pages/messagecenter/messagecenter',
  'pages/profile/services',
  'pages/profile/privacy',
  'pages/profile/edit',
  'pages/profile/records',
  'pages/profile/record',
];
const expectedPages = [...entryPages, ...flowPages];

assert.deepStrictEqual(appConfig.pages, expectedPages, '应保留原生页面并显式登记 T10 预算参数及旧预算兼容路由');
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
  // 2026-09-29 决策 2：选择页收敛到生成页内联候选，原“选定并生成立面/完成方案”由页内 CTA 承担
  '选它',
  '生成立面方案',
  '查看最终方案',
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
assert.match(customTabData, /text: 'AI设计',[^\n]*icon: 'edit-1', activeIcon: 'edit-1-filled'/, 'AI 设计底部导航应使用已交付的 TDesign 编辑图标及选中态');
assert.match(
  read('miniprogram/custom-tab-bar/index.wxml'),
  /<t-icon name="{{current === index \? item\.activeIcon : item\.icon}}"/,
  '底部导航应按当前页面切换已在本地图标字库中验证的图标',
);

const homeMarkup = read('miniprogram/pages/home/index.wxml');
assert.match(homeMarkup, /class="hero-art"/, '首页主视觉应使用拆分后的房屋素材并保留文字叠加结构');

const detailMarkup = read('miniprogram/pages/library/detail.wxml');
assert.match(detailMarkup, /src="{{hero.url}}"/, '户型详情必须展示当前案例真实封面');
assert.doesNotMatch(detailMarkup, /\/assets\/v12\/(villa-detail|front-elevation|plan-b)/, '缺图不能以固定示例图冒充');
assert.match(detailMarkup, /wx:for="{{drawings}}"/, '图纸标签按实际资产动态生成');
assert.match(detailMarkup, /设计说明/, '保留设计说明结构');
assert.doesNotMatch(detailMarkup, /class="stats"/, '户型详情不应保留效果图中不存在的四列统计模块');

const aiDesignMarkup = read('miniprogram/pages/ai-design/index.wxml');
assert.doesNotMatch(registeredSource, /show-notice/, '消息入口迁移到“我的”后，各页右上角不应再显示铃铛');
assert.doesNotMatch(read('miniprogram/components/v12-navbar/index.wxml'), /notice-button|notification/, '统一导航栏不应保留消息入口');
assert.match(aiDesignMarkup, /class="ai-page-title">AI设计<\/view>/, 'AI 设计页标题应位于品牌栏下一行');
const profileMarkup = read('miniprogram/pages/profile/index.wxml');
assert.match(profileMarkup, /bindtap="openMessages"[^>]*>[\s\S]*?消息中心/, '“我的”页面应提供消息中心入口');
assert.match(profileMarkup, /class="message-count"/, '“我的”页面应展示未读消息数量');
assert.match(read('miniprogram/custom-tab-bar/index.wxml'), /class="tabbar-badge"/, '“我的”Tab 应在有未读消息时展示角标');
const aiDesignStyles = read('miniprogram/pages/ai-design/index.scss');
assert.match(aiDesignStyles, /\.step-intro\s*\{[^}]*flex-wrap:\s*wrap/, 'AI 步骤说明应允许换行，避免 375px 及更窄设备横向溢出');
assert.match(aiDesignStyles, /\.cost-summary\s*\{[^}]*display:\s*grid/, '出图费用应使用独立网格而非挤在数量选择行');
assert.match(aiDesignStyles, /\.cost-summary span\s*\{[^}]*grid-column:\s*1\s*\/\s*-1/, '余额应独占下一行，避免小屏上与报价互相挤压');

const theme = `${read('miniprogram/styles/v12-tokens.scss')}\n${read('miniprogram/styles/v12-theme.scss')}`;
for (const token of ['#f7f4ee', '#fffdf8', '#6a3b1b', '#7b5532', '#c8a679', '#2f2925', '#786858', '#e8ded1']) {
  assert.match(theme.toLowerCase(), new RegExp(token), `V1.2 主题应包含色值 ${token}`);
}

console.log('众墅之家 V1.2 原生小程序结构与离线图标检查通过');
