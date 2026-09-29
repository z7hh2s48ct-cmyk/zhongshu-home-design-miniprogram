// design-spacing-normalize.mjs —— 间距归并工具（一次性执行，保留作为规则档案）
// 用法：node scripts/design-spacing-normalize.mjs [--dry]
// 规则：padding / margin / gap / row-gap / column-gap 声明段内的 rpx 值归并到 4rpx 倍数网格，
//       余 2 的值向上取（紧端只放宽不收紧）；含 calc()/env() 的声明跳过；
//       同行其他属性（border/width/height 等）不受影响。

import { readFileSync, writeFileSync, globSync } from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..', '前端程序', 'miniprogram');
const DRY = process.argv.includes('--dry');

const snap = (n) => {
  const r = n % 4;
  if (r === 0) return n;
  if (r === 1) return n - 1;
  if (r === 3) return n + 1;
  return n + 2; // 余 2 → 向上，拥挤处只放宽
};

const files = [
  ...globSync(`${ROOT}/pages/**/*.scss`),
  ...globSync(`${ROOT}/components/v12-*/index.scss`),
  ...globSync(`${ROOT}/styles/*.scss`),
  `${ROOT}/custom-tab-bar/index.scss`,
];

let total = 0;
for (const f of files) {
  const src = readFileSync(f, 'utf8');
  let count = 0;
  const out = src.split(/(?=\r?\n)/).map((line) => {
    if (line.trim().startsWith('//')) return line;
    // 只在目标属性的声明段（prop: value 到分号）内替换，绝不动同行其他属性；
    // 覆盖简写与分写（margin-top / padding-left 等长属性同样归并）
    return line.replace(/((?:(?:padding|margin)(?:-(?:top|bottom|left|right))?|row-gap|column-gap|gap)\s*:\s*)([^;]+)/g, (m, prop, vals) => {
      if (/calc\(|env\(/.test(vals)) return m;
      const next = vals.replace(/(-?\d+)rpx/g, (mm, num) => {
        const n = Number(num);
        const s = snap(Math.abs(n));
        if (s === Math.abs(n)) return mm;
        count++;
        return (n < 0 ? -s : s) + 'rpx';
      });
      return prop + next;
    });
  }).join('');
  if (out !== src && !DRY) writeFileSync(f, out);
  total += count;
  if (count) console.log(`${path.relative(ROOT, f)}  spacing:${count}`);
}
console.log(DRY ? `(dry run) 共 ${total} 处待归并` : `间距归并完成，共 ${total} 处`);
