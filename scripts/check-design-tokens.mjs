// check-design-tokens.mjs —— 设计规范防回归检查
// 用法：node scripts/check-design-tokens.mjs   （违规时退出码 1，可接入 pre-commit / CI）
// 规则：
//   R1 自有 scss 禁止裸 hex（颜色一律引用 styles/v12-tokens.scss 变量；注释行豁免）
//   R2 font-size 只允许 $v12-font-* token 或展示型特例字面量
//   R3 border-radius 只允许 $v12-radius-* token、0、50%
//   R4 wxml 内联样式禁止 hex 颜色
//   R5 t-icon size 只允许 16/20/24/32/64px；color 只允许规范色板
// 豁免范围：styles/（token 源）、components/tdesign-miniprogram（三方库）、app.scss（icon 字库）

import { readFileSync, globSync } from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..', '前端程序', 'miniprogram');

const FONT_EXCEPTIONS = new Set([45, 46, 48, 51, 52, 54, 66, 70, 82]); // 展示型大字号特例（各 1 处，含首页 hero/余额数字）
const ICON_SIZES = new Set([16, 20, 24, 32, 64]);
const ICON_COLORS = new Set([
  '#7b5532', // $v12-primary
  '#8b4818', // $v12-primary-deep
  '#6a3b1b', // $v12-brand
  '#b9690b', // $v12-gold-deep
  '#c8a679', // $v12-gold
  '#786858', // $v12-text-2
  '#a89a8c', // $v12-text-3
  '#b6b4b0', // $v12-text-4
  '#2f2925', // $v12-text-1
  '#49745c', // $v12-success
  '#b45545', // $v12-error
]);

const scssFiles = [
  ...globSync(`${ROOT}/pages/**/*.scss`),
  ...globSync(`${ROOT}/components/v12-*/index.scss`),
  `${ROOT}/custom-tab-bar/index.scss`,
];
const wxmlFiles = [
  ...globSync(`${ROOT}/pages/**/*.wxml`),
  ...globSync(`${ROOT}/components/v12-*/index.wxml`),
  `${ROOT}/custom-tab-bar/index.wxml`,
];

const problems = [];
const rel = (f) => path.relative(ROOT, f);

for (const f of scssFiles) {
  const lines = readFileSync(f, 'utf8').split(/\r?\n/);
  lines.forEach((line, i) => {
    const at = `${rel(f)}:${i + 1}`;
    if (line.trim().startsWith('//')) return;
    // R1 裸 hex
    for (const m of line.matchAll(/#[0-9a-fA-F]{3,8}\b/g)) problems.push(`R1 裸hex ${m[0]}  @ ${at}`);
    // R2 字号
    for (const m of line.matchAll(/font-size:\s*(\d+)rpx/g)) {
      if (!FONT_EXCEPTIONS.has(Number(m[1]))) problems.push(`R2 字号越阶 ${m[1]}rpx  @ ${at}`);
    }
    // R3 圆角
    for (const m of line.matchAll(/border-radius:\s*([^;]+)/g)) {
      const val = m[1].trim();
      if (val === '0' || val === '50%') continue;
      if (/^\$v12-radius/.test(val)) continue;
      if (/^(\$v12-radius-[a-z]+|\d+rpx|0)(\s+(\$v12-radius-[a-z]+|\d+rpx|0))*$/.test(val)) {
        // 多值圆角：逐个检查
        const bad = val.split(/\s+/).filter((v) => /^\d+rpx$/.test(v));
        if (bad.length) problems.push(`R3 圆角越阶 ${bad.join(' ')}  @ ${at}`);
      } else {
        problems.push(`R3 圆角非规范 "${val}"  @ ${at}`);
      }
    }
  });
}

for (const f of wxmlFiles) {
  const src = readFileSync(f, 'utf8');
  // R4 内联样式 hex
  for (const m of src.matchAll(/style="[^"]*#[0-9a-fA-F]{3,8}[^"]*"/g)) {
    problems.push(`R4 内联样式hex  @ ${rel(f)}: ${m[0].slice(0, 60)}`);
  }
  // R5 t-icon 尺寸与颜色
  for (const m of src.matchAll(/<t-icon\b[^>]*>/g)) {
    const tag = m[0];
    const size = tag.match(/\bsize="(\d+)px"/);
    if (size && !ICON_SIZES.has(Number(size[1]))) problems.push(`R5 图标尺寸越档 ${size[1]}px  @ ${rel(f)}`);
    const color = tag.match(/\bcolor="(#[0-9a-fA-F]{3,8})"/);
    if (color && !ICON_COLORS.has(color[1].toLowerCase())) problems.push(`R5 图标颜色越板 ${color[1]}  @ ${rel(f)}`);
  }
}

if (problems.length) {
  console.error(`设计规范检查未通过（${problems.length} 处）：`);
  for (const p of problems) console.error('  ' + p);
  process.exit(1);
}
console.log(`设计规范检查通过（scss ${scssFiles.length} 个、wxml ${wxmlFiles.length} 个）。`);
