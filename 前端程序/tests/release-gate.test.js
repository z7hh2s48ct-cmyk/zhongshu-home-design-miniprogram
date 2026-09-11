const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const { scanContent, scanTree, collectTargets } = require('../scripts/check-release.js');

// T13-08 发布门禁逻辑专项：以 fixture 验证三类规则命中/放行，确保门禁本身可靠（不因开发期 config.js 保留
// localhost 而污染单测——那是发布门禁 npm run check:release 的职责，与此处逻辑校验分离）。

test('scanContent 标记 touristappid 占位 AppID', () => {
  const violations = scanContent('project.config.json', '{ "appid": "touristappid" }');
  assert.ok(violations.some((v) => v.ruleId === 'touristappid'), 'touristappid 必须被拦截');
});

test('scanContent 标记 localhost 与 127.0.0.1 开发地址', () => {
  assert.ok(scanContent('config.js', "apiBase: 'http://localhost:48080'").some((v) => v.ruleId === 'dev-host'));
  assert.ok(scanContent('config.js', "apiBase: 'http://127.0.0.1:48080'").some((v) => v.ruleId === 'dev-host'));
});

test('scanContent 标记 http:// 明文，放行 https:// 正式域名', () => {
  assert.ok(scanContent('config.js', "apiBase: 'http://api.zhongshu.com'").some((v) => v.ruleId === 'plain-http'));
  assert.equal(scanContent('config.js', "apiBase: 'https://api.zhongshu.com'").length, 0, 'https 正式域名不应触发任何规则');
});

test('http://localhost 同时命中 dev-host 与 plain-http（双重违规均须报告）', () => {
  const violations = scanContent('config.js', "apiBase: 'http://localhost:48080'");
  const ids = violations.map((v) => v.ruleId);
  assert.ok(ids.includes('dev-host') && ids.includes('plain-http'), ids.join(','));
});

test('scanContent 标记 JS/JSON 转义形 http:\\/\\/ 明文，放行转义 https:\\/\\/（round-4 P2#1）', () => {
  // JSON 允许以 \/ 转义正斜杠，故发布源码里的明文地址可能写作 http:\/\/，字面 /http:\/\// 会漏检（codex round-4 P2#1）。
  assert.ok(
    scanContent('app.json', '{ "apiBase": "http:\\/\\/api.example.com" }').some((v) => v.ruleId === 'plain-http'),
    'JSON 转义形 http:\\/\\/ 须被 plain-http 拦截'
  );
  assert.ok(
    scanContent('config.js', "apiBase: 'http:\\/\\/api.example.com'").some((v) => v.ruleId === 'plain-http'),
    'JS 字符串转义形 http:\\/\\/ 须被 plain-http 拦截'
  );
  assert.equal(
    scanContent('app.json', '{ "apiBase": "https:\\/\\/api.example.com" }').length, 0,
    '转义 https:\\/\\/ 正式域名不应触发任何规则（HTTPS 仍放行）'
  );
  const ids = scanContent('config.js', "apiBase: 'http:\\/\\/localhost:48080'").map((v) => v.ruleId);
  assert.ok(ids.includes('dev-host') && ids.includes('plain-http'), `转义形 http:\\/\\/localhost 须同时命中 dev-host 与 plain-http：${ids.join(',')}`);
});

test('scanContent 逐行定位命中行号与去空白片段', () => {
  const violations = scanContent('a.js', 'clean line\n  localhost here  \nclean again');
  assert.equal(violations.length, 1);
  assert.equal(violations[0].line, 2, '行号从 1 计');
  assert.equal(violations[0].snippet, 'localhost here', '片段应去除首尾空白');
});

test('collectTargets 纳入外层发布配置、排除本机私有配置', () => {
  const targets = collectTargets().map((t) => t.split(path.sep).join('/'));
  assert.ok(targets.includes('project.config.json'), '外层发布工程配置须纳入扫描');
  assert.ok(!targets.some((t) => t.endsWith('project.private.config.json')), '本机私有配置不随发布上传，须排除');
  assert.ok(!targets.some((t) => t.includes('tdesign-miniprogram')), '第三方 vendored 组件库须排除，避免误报');
});

test('真实发布树中仅指定开发配置 config.js 可含门禁违规，杜绝其它文件混入 localhost/http/touristappid', () => {
  // 子集断言：允许集＝config.js（开发期 API 地址唯一指定落点）。正式域名接入后 config.js 违规自动消失，
  //   空集仍是允许集的子集，断言不脆；而任何其它文件新混入 localhost/http/touristappid 都会立即失败。
  //   刻意不硬断言 config.js 当前必含 localhost——那会让「切正式 HTTPS 域名后门禁转绿」反被本测试判失败（codex round-1 P1#2）；
  //   dev/prod 两种配置的规则命中/放行已由上方 scanContent 的 fixture 用例覆盖。
  const violatingFiles = new Set();
  for (const violation of scanTree()) {
    violatingFiles.add(violation.path.split(path.sep).join('/'));
  }
  const allowed = new Set(['miniprogram/utils/config.js']);
  for (const file of violatingFiles) {
    assert.ok(allowed.has(file), `发布门禁：非预期文件含 localhost/http/touristappid：${file}`);
  }
});
