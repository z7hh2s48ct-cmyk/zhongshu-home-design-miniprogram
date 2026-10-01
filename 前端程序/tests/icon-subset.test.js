const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

// 背景：TDesign t-icon 的字体本体以 base64 内嵌在 app.scss（icon.wxss 只引用 family 't'）。
// 2026-10-02 起字体做按用子集化（scripts/subset-icon-font.py，244KB→4KB）。本测试守护两件事：
//   1) 字体本体不被当作"死资源"删除（它就是全端图标的字形来源）；
//   2) 新增图标名后必须重跑子集脚本，否则该图标在真机不渲染。
const root = path.resolve(__dirname, '../miniprogram');
const manifest = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../scripts/icon-subset-manifest.json'), 'utf8'));

function collectUsedIconNames() {
  const used = new Set();
  const stripComparisons = (expr) => expr.replace(/[=!]==?\s*'[^']*'/g, '');
  const namesIn = (expr) => {
    const inner = /\{\{([\s\S]*)\}\}/.exec(expr);
    return inner
      ? Array.from(stripComparisons(inner[1]).matchAll(/'([^']+)'/g), (m) => m[1])
      : [expr.trim()];
  };
  const walk = (dir, ext) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (entry.name === 'tdesign-miniprogram') continue;
        walk(full, ext);
      } else if (entry.name.endsWith(ext)) {
        const text = fs.readFileSync(full, 'utf8');
        if (ext === '.wxml') {
          for (const m of text.matchAll(/<t-icon\b[^>]*?\bname="([^"]+)"/g)) {
            namesIn(m[1]).filter(Boolean).forEach((n) => used.add(n));
          }
          for (const m of text.matchAll(/\bicon="([^"]+)"/g)) {
            namesIn(m[1]).filter(Boolean).forEach((n) => used.add(n));
          }
        } else {
          for (const m of text.matchAll(/\b(?:icon|activeIcon|inactiveIcon)\s*:\s*['"]([^'"]+)['"]/g)) {
            if (manifest.nameToCodepoint[m[1]]) used.add(m[1]);  // wx.showToast 的 icon:'none' 等非字体用法排除
          }
        }
      }
    }
  };
  walk(root, '.wxml');
  walk(root, '.js');
  return used;
}

test('app.scss 必须内嵌 family t 的图标字体（TDesign t-icon 的字形本体，删了全端图标消失）', () => {
  const scss = fs.readFileSync(path.join(root, 'app.scss'), 'utf8');
  assert.match(scss, /@font-face\s*\{\s*font-family:\s*'t';/);
  assert.match(scss, /data:font\/woff;base64,/);
});

test('app.scss 内嵌的是子集字体而非 244KB 全量字体', () => {
  const scss = fs.readFileSync(path.join(root, 'app.scss'), 'utf8');
  const b64 = /data:font\/woff;base64,([^']+)'/.exec(scss)[1];
  assert.ok(b64.length <= 16 * 1024, `内嵌字体应为子集（≤16KB base64），当前 ${Math.round(b64.length / 1024)}KB——若确实新增大量图标，请同步上调本阈值`);
});

test('代码中用到的每个图标名都必须在子集清单里（新增图标后重跑 scripts/subset-icon-font.py）', () => {
  const used = collectUsedIconNames();
  const unknown = Array.from(used).filter((n) => !manifest.nameToCodepoint[n]);
  assert.deepEqual(unknown, [], `以下图标名不在子集清单，真机将不渲染：${unknown.join('、')}。请运行 python scripts/subset-icon-font.py 重新子集化`);
});

test('子集清单覆盖的名字集合与代码实际使用一致（防止清单腐化）', () => {
  const used = collectUsedIconNames();
  const listed = new Set(manifest.subsetUsedNames);
  const stale = Array.from(listed).filter((n) => !used.has(n));
  const missing = Array.from(used).filter((n) => !listed.has(n));
  assert.deepEqual(stale.concat(missing), [], '清单与实际使用不一致，请重跑 python scripts/subset-icon-font.py');
});
