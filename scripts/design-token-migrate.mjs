// design-token-migrate.mjs —— 设计 Token 归并迁移工具（一次性执行，保留作为映射档案）
// 用法：node scripts/design-token-migrate.mjs [--dry]
// 作用：
//   1) 自有 scss 裸 hex → v12 token 变量（映射表见下，来源：项目文档/15 排查报告）
//   2) font-size / border-radius → 字阶与圆角 token
//   3) wxml 中 t-icon 的 size/color 归档到规范值
// 不处理：styles/（token 定义源）、components/tdesign-miniprogram（三方库）、注释行。

import { readFileSync, writeFileSync } from 'node:fs';
import { globSync } from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..', '前端程序', 'miniprogram');
const DRY = process.argv.includes('--dry');

// ---------- 色值 → token 映射（scss 用变量） ----------
const COLOR_MAP = {
  // 主棕
  '#6a3b1b': '$v12-brand', '#7b5532': '$v12-primary', '#7c5a2e': '$v12-primary', '#81502c': '$v12-primary',
  '#8b4818': '$v12-primary-deep', '#8a4a1a': '$v12-primary-deep', '#7b3f17': '$v12-primary-deep',
  '#8a3517': '$v12-primary-deep', '#884312': '$v12-primary-deep', '#7c3f16': '$v12-primary-deep',
  '#82470f': '$v12-primary-deep', '#8a4617': '$v12-primary-deep', '#843d13': '$v12-primary-deep',
  '#8a4b20': '$v12-primary-deep', '#8d4b20': '$v12-primary-deep', '#8b4b13': '$v12-primary-deep',
  '#8a4d23': '$v12-primary-deep', '#875126': '$v12-primary-deep', '#774619': '$v12-primary-deep',
  '#8a3d0d': '$v12-primary-deep', '#603009': '$v12-primary-deep',
  // 金/琥珀
  '#c8a679': '$v12-gold', '#c89a63': '$v12-gold', '#c9a36b': '$v12-gold', '#d9b98f': '$v12-gold',
  '#dab57a': '$v12-gold', '#e7c49e': '$v12-gold', '#e2c6a4': '$v12-gold', '#edc9a8': '$v12-gold',
  '#dcc8b3': '$v12-gold', '#aa6e45': '$v12-gold', '#8a6a45': '$v12-gold', '#a08760': '$v12-gold',
  '#b9690b': '$v12-gold-deep', '#9a570e': '$v12-gold-deep', '#af690b': '$v12-gold-deep',
  '#b35c07': '$v12-gold-deep', '#b0621c': '$v12-gold-deep', '#a86a35': '$v12-gold-deep',
  '#b87407': '$v12-gold-deep', '#c68027': '$v12-gold-deep', '#dc8b00': '$v12-gold-deep',
  '#e88d00': '$v12-gold-deep', '#a65b25': '$v12-gold-deep',
  // 文字深色
  '#2f2925': '$v12-text-1', '#121314': '$v12-text-1', '#171717': '$v12-text-1', '#151515': '$v12-text-1',
  '#3c2f22': '$v12-text-1', '#3e342c': '$v12-text-1',
  // 次级文字（暖灰与冷灰统一到暖阶）
  '#786858': '$v12-text-2', '#5a4c3f': '$v12-text-2', '#5d5149': '$v12-text-2',
  '#52565f': '$v12-text-2', '#55585f': '$v12-text-2', '#5d626b': '$v12-text-2', '#777b83': '$v12-text-2',
  '#858893': '$v12-text-2', '#707070': '$v12-text-2', '#777': '$v12-text-2', '#666': '$v12-text-2', '#696969': '$v12-text-2',
  '#817367': '$v12-text-3', '#8d8580': '$v12-text-3', '#8d8178': '$v12-text-3', '#938477': '$v12-text-3',
  '#95816e': '$v12-text-3', '#a09183': '$v12-text-3', '#a0978f': '$v12-text-3', '#9a8f80': '$v12-text-3',
  '#9a938c': '$v12-text-3', '#a09384': '$v12-text-3', '#a09a92': '$v12-text-3', '#a89a8c': '$v12-text-3',
  '#aaa19a': '$v12-text-3', '#b5a18f': '$v12-text-3', '#888': '$v12-text-3', '#777a82': '$v12-text-3',
  '#858585': '$v12-text-3', '#868686': '$v12-text-3', '#818181': '$v12-text-3', '#939393': '$v12-text-3',
  '#999': '$v12-text-3', '#aaa': '$v12-text-3',
  // 占位/禁用
  '#b6b4b0': '$v12-text-4', '#bdbdbd': '$v12-text-4', '#c5c7c9': '$v12-text-4',
  '#cfc5ba': '$v12-disabled', '#cfcfce': '$v12-disabled', '#bdab9d': '$v12-disabled',
  '#e9e5df': '$v12-disabled', '#cfc9c3': '$v12-disabled', '#c9c1b8': '$v12-disabled',
  '#c9c0b7': '$v12-disabled', '#d5cec5': '$v12-disabled',
  // 分隔线/描边
  '#e8ded1': '$v12-divider', '#e6e7e9': '$v12-divider', '#e2e4e8': '$v12-divider', '#e8e0d5': '$v12-divider',
  '#e6e0d8': '$v12-divider', '#e5d9ca': '$v12-divider', '#e7e4e0': '$v12-divider', '#e4dfd8': '$v12-divider',
  '#ddd6cc': '$v12-divider',
  // 面
  '#f7f4ee': '$v12-page', '#faf9f7': '$v12-page', '#fffdf8': '$v12-surface', '#fffdf9': '$v12-surface',
  '#fdf5e9': '$v12-warning-bg', '#fdf4e8': '$v12-warning-bg',
  '#f4f0e9': '$v12-surface-alt', '#f6eee5': '$v12-surface-alt', '#f5eee6': '$v12-surface-alt',
  '#f3eee7': '$v12-surface-alt', '#f5eee4': '$v12-surface-alt', '#f0ebe3': '$v12-surface-alt',
  '#eee9e2': '$v12-surface-alt', '#eeebe7': '$v12-surface-alt', '#faf6f0': '$v12-surface-alt',
  '#fbf6ee': '$v12-surface-alt', '#fbf6ed': '$v12-surface-alt', '#fbf4e9': '$v12-surface-alt',
  '#fbf3e9': '$v12-surface-alt', '#fcf7ef': '$v12-surface-alt', '#fffaf3': '$v12-surface-alt',
  '#fff8ef': '$v12-surface-alt', '#f8f2e9': '$v12-surface-alt', '#eef2f3': '$v12-surface-alt',
  '#f5e5d0': '$v12-gold-bg', '#f4e8d9': '$v12-gold-bg', '#faead7': '$v12-gold-bg', '#f3e7d7': '$v12-gold-bg',
  '#eee5d8': '$v12-gold-bg', '#efe3d4': '$v12-gold-bg', '#faf0e4': '$v12-gold-bg', '#f0eae2': '$v12-gold-bg',
  '#fff3dc': '$v12-gold-bg', '#ecd9bf': '$v12-gold-bg', '#ecd1ac': '$v12-gold-bg', '#ead8bd': '$v12-gold-bg',
  '#d8c3ae': '$v12-gold', '#98938c': '$v12-text-3', '#85807b': '$v12-text-3',
  // 功能色
  '#b93b2e': '$v12-error', '#a33b21': '$v12-error', '#c74c46': '$v12-error', '#bd3434': '$v12-error',
  '#bc3d24': '$v12-error', '#a52a2a': '$v12-error', '#9b351f': '$v12-error', '#a64029': '$v12-error',
  '#b45545': '$v12-error', '#e44545': '$v12-error',
  '#fcebea': '$v12-error-bg', '#fbeae5': '$v12-error-bg', '#fff7f5': '$v12-error-bg',
  '#49745c': '$v12-success', '#36863b': '$v12-success', '#319149': '$v12-success', '#6f8f5c': '$v12-success',
  '#cfe0c4': '$v12-success-bg', '#f6faf3': '$v12-success-bg',
  // 白
  '#ffffff': '$v12-white', '#fff': '$v12-white',
};

// ---------- 字阶（奇数/离档值就近归并 → token） ----------
const FONT_MAP = {
  18: '$v12-font-xs', 19: '$v12-font-xs', 20: '$v12-font-xs',
  21: '$v12-font-sm', 22: '$v12-font-sm',
  23: '$v12-font-body', 24: '$v12-font-body',
  25: '$v12-font-md', 26: '$v12-font-md',
  27: '$v12-font-lg', 28: '$v12-font-lg', 29: '$v12-font-lg', 30: '$v12-font-lg',
  31: '$v12-font-xl', 32: '$v12-font-xl',
  34: '$v12-font-title', 35: '$v12-font-title', 36: '$v12-font-title',
  38: '$v12-font-hero', 40: '$v12-font-hero', 42: '$v12-font-hero',
  // 45/46/48/51/52/54/66/70/82 为展示型特例，保留原值
};

// ---------- 圆角（就近归并 → token） ----------
const RADIUS_MAP = {
  5: '$v12-radius-xs', 6: '$v12-radius-xs', 7: '$v12-radius-xs', 8: '$v12-radius-xs', 9: '$v12-radius-xs',
  10: '$v12-radius-sm', 12: '$v12-radius-sm', 13: '$v12-radius-sm',
  14: '$v12-radius-md', 15: '$v12-radius-md', 16: '$v12-radius-md', 18: '$v12-radius-md',
  20: '$v12-radius-lg', 22: '$v12-radius-lg', 24: '$v12-radius-lg', 25: '$v12-radius-lg',
  26: '$v12-radius-lg', 28: '$v12-radius-lg', 999: '$v12-radius-full',
  // 40rpx 与 50% 为特例，保留
};

// ---------- wxml t-icon 规范值 ----------
const ICON_COLOR_MAP = {
  '#82470f': '#7b5532', '#7b3f17': '#7b5532', '#8a4a1a': '#7b5532', '#884312': '#7b5532',
  '#8a572f': '#7b5532', '#8a3517': '#7b5532',
  '#b35c07': '#b9690b', '#b86600': '#b9690b', '#c78217': '#b9690b', '#d39b39': '#b9690b',
  '#9a6d39': '#786858',
  '#8d8178': '#a89a8c', '#817367': '#a89a8c', '#96908b': '#a89a8c', '#9a938c': '#a89a8c',
  '#8a8178': '#a89a8c', '#aaa19a': '#a89a8c', '#777a82': '#a89a8c', '#888b91': '#a89a8c',
  '#898c94': '#a89a8c', '#888': '#a89a8c', '#c5c7c9': '#b6b4b0',
  '#777': '#786858', '#161616': '#2f2925',
  '#559b54': '#49745c', '#4da05b': '#49745c', '#20be42': '#49745c',
};
const ICON_SIZE_MAP = { 14: '16', 15: '16', 17: '16', 18: '20', 22: '20', 23: '24', 25: '24', 26: '24', 27: '24', 29: '32', 30: '32', 36: '32' };

const isComment = (line) => line.trim().startsWith('//');
const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

const colorKeys = Object.keys(COLOR_MAP).sort((a, b) => b.length - a.length);

function migrateScss(content) {
  let colors = 0, fonts = 0, radii = 0;
  const out = content.split(/(?=\r?\n)/).map((line) => {
    if (isComment(line)) return line;
    let next = line;
    for (const hex of colorKeys) {
      const re = new RegExp(escapeRe(hex), 'gi');
      next = next.replace(re, () => { colors++; return COLOR_MAP[hex]; });
    }
    next = next.replace(/(font-size:\s*)(\d+)rpx/g, (m, p, n) => {
      const v = FONT_MAP[Number(n)];
      return v ? (fonts++, p + v) : m;
    });
    next = next.replace(/(border-radius:\s*)([^;]+)/g, (m, p, vals) => {
      const mapped = vals.replace(/(\d+)rpx/g, (mm, n) => {
        const v = RADIUS_MAP[Number(n)];
        return v ? (radii++, v) : mm;
      });
      return p + mapped;
    });
    return next;
  }).join('');
  return { out, colors, fonts, radii };
}

function migrateWxml(content) {
  let sizes = 0, iconColors = 0;
  let out = content.replace(/(<t-icon\b[^>]*\bsize=")(\d+)(px")/g, (m, a, n, z) => {
    const v = ICON_SIZE_MAP[Number(n)];
    return v ? (sizes++, a + v + z) : m;
  });
  for (const [from, to] of Object.entries(ICON_COLOR_MAP)) {
    const re = new RegExp(escapeRe(from), 'gi');
    out = out.replace(re, () => { iconColors++; return to; });
  }
  return { out, sizes, iconColors };
}

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

const leftovers = [];
for (const f of scssFiles) {
  const src = readFileSync(f, 'utf8');
  const { out, colors, fonts, radii } = migrateScss(src);
  if (out !== src && !DRY) writeFileSync(f, out);
  const left = [...out.matchAll(/#[0-9a-fA-F]{3,8}\b/g)]
    .map((m) => m[0].toLowerCase())
    .filter((h) => !(h in COLOR_MAP));
  if (left.length) leftovers.push([path.relative(ROOT, f), [...new Set(left)].join(' ')]);
  if (colors || fonts || radii) console.log(`${path.relative(ROOT, f)}  colors:${colors} fonts:${fonts} radii:${radii}`);
}
for (const f of wxmlFiles) {
  const src = readFileSync(f, 'utf8');
  const { out, sizes, iconColors } = migrateWxml(src);
  if (out !== src && !DRY) writeFileSync(f, out);
  if (sizes || iconColors) console.log(`${path.relative(ROOT, f)}  iconSizes:${sizes} iconColors:${iconColors}`);
}

if (leftovers.length) {
  console.log('\n—— 未映射残留 hex（需人工处理）——');
  for (const [f, hs] of leftovers) console.log(`  ${f}: ${hs}`);
} else {
  console.log('\n无未映射残留 hex');
}
console.log(DRY ? '\n(dry run，未写盘)' : '\n迁移完成');
