'use strict';

// T13-08 发布门禁：正式 HTTPS 域名与 AppID 到位前不放行发布。
// 扫描即将发布的 miniprogram/ 源码与工程配置，拦截三类“仅开发期合法、发布必须消除”的内容：
//   1) touristappid   —— 占位 AppID，无法通过微信审核发布（外层配置曾遗留，B02 已在 T13-08 统一为正式 AppID）；
//   2) localhost/127.0.0.1 —— 开发 API 地址，发布须替换为正式 HTTPS 域名；
//   3) http://        —— 明文地址（含 JS/JSON 中的转义形 http:\/\/，即正斜杠被反斜杠转义），微信小程序生产环境强制 HTTPS。
// 定位：本脚本是“发布前独立门禁”（npm run check:release），与单元测试（npm test）刻意分离。
//   按 T13-08 决策 3，config.js 开发期保留 http://localhost，故正式域名接入前本门禁“应当”报红——
//   这是预期的“尚未就绪”信号，而非缺陷；域名接入后自动转绿。扫描范围排除第三方 vendored 代码与本机私有配置，
//   只对本项目自有的可发布源码与工程配置负责，避免库自带 http/schema 造成误报噪声。

const fs = require('fs');
const path = require('path');

const FRONTEND_ROOT = path.resolve(__dirname, '..');
const SCAN_EXTENSIONS = new Set(['.js', '.json', '.wxml', '.wxss', '.scss', '.ts', '.wxs']);
// 第三方 vendored 组件库与本机私有配置不参与发布门禁：前者 http/schema 属库自身、非本项目发布配置，
// 后者（project.private.config.json）为机器本地设置、不随发布上传。
const EXCLUDED_DIRECTORIES = new Set(['tdesign-miniprogram', 'miniprogram_npm', 'node_modules']);
const EXCLUDED_FILES = new Set(['project.private.config.json']);

const RULES = [
  { id: 'touristappid', desc: 'touristappid 占位 AppID，发布须为正式 AppID', pattern: /touristappid/i },
  { id: 'dev-host', desc: 'localhost/127.0.0.1 开发地址，发布须换正式 HTTPS 域名', pattern: /localhost|127\.0\.0\.1/i },
  { id: 'plain-http', desc: 'http:// 明文地址（含 JS/JSON 转义形 http:\\/\\/），微信生产强制 HTTPS', pattern: /http:(?:\\*\/){2}/i },
];

// 扫描单文件内容，逐行返回命中规则（含行号与去空白片段），便于门禁报告精确定位。
function scanContent(relativePath, content) {
  const violations = [];
  const lines = String(content).split(/\r?\n/);
  lines.forEach((line, index) => {
    for (const rule of RULES) {
      if (rule.pattern.test(line)) {
        violations.push({
          ruleId: rule.id,
          desc: rule.desc,
          path: relativePath,
          line: index + 1,
          snippet: line.trim(),
        });
      }
    }
  });
  return violations;
}

function walk(relativeDirectory, targets) {
  const absolute = path.join(FRONTEND_ROOT, relativeDirectory);
  if (!fs.existsSync(absolute)) return;
  for (const entry of fs.readdirSync(absolute, { withFileTypes: true })) {
    if (entry.name.startsWith('.')) continue;
    const relativePath = path.join(relativeDirectory, entry.name);
    if (entry.isDirectory()) {
      if (EXCLUDED_DIRECTORIES.has(entry.name)) continue;
      walk(relativePath, targets);
    } else if (!EXCLUDED_FILES.has(entry.name) && SCAN_EXTENSIONS.has(path.extname(entry.name))) {
      targets.push(relativePath);
    }
  }
}

// 发布扫描目标：miniprogram/ 全量自有源码 + 外层发布工程配置（位于 miniprogram/ 之外、DevTools 推荐发布根）。
function collectTargets() {
  const targets = [];
  walk('miniprogram', targets);
  if (fs.existsSync(path.join(FRONTEND_ROOT, 'project.config.json'))) targets.push('project.config.json');
  return targets;
}

function scanTree() {
  const violations = [];
  for (const relativePath of collectTargets()) {
    const content = fs.readFileSync(path.join(FRONTEND_ROOT, relativePath), 'utf8');
    violations.push(...scanContent(relativePath, content));
  }
  return violations;
}

function main() {
  const violations = scanTree();
  if (violations.length === 0) {
    console.log('✓ 发布门禁通过：无 touristappid 占位、无 localhost/127.0.0.1 开发地址、无 http:// 明文');
    return 0;
  }
  console.error(`✗ 发布门禁未通过（${violations.length} 处待消除；正式 HTTPS 域名与 AppID 到位前不放行发布）：`);
  for (const violation of violations) {
    const relativePath = violation.path.split(path.sep).join('/');
    console.error(`  [${violation.ruleId}] ${relativePath}:${violation.line}  ${violation.snippet}`);
    console.error(`      → ${violation.desc}`);
  }
  console.error('提示：开发期 config.js 保留 http://localhost 属预期，故当前报红＝尚未就绪；正式域名接入后本门禁自动转绿。');
  return 1;
}

if (require.main === module) {
  process.exitCode = main();
}

module.exports = { RULES, scanContent, collectTargets, scanTree, main, FRONTEND_ROOT };
