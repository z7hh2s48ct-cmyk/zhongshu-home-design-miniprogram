const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const root = path.resolve(__dirname, '../miniprogram');
const markup = fs.readFileSync(path.join(root, 'pages/home/index.wxml'), 'utf8');
const styles = fs.readFileSync(path.join(root, 'pages/home/index.scss'), 'utf8');

test('首页使用接近主视觉容器比例的完整场景（2026-10 包体积整改后为压缩 JPG），避免窄图被再次放大裁切', () => {
  assert.match(markup, /class="hero-art" src="\/assets\/v12\/home-hero-scene\.jpg" mode="aspectFill"/);
  const scene = fs.readFileSync(path.join(root, 'assets/v12/home-hero-scene.jpg'));
  assert.equal(scene.subarray(0, 2).toString('hex'), 'ffd8', '主视觉应为 JPG（无透明通道的场景图用 JPG 省包体）');
  assert.ok(scene.length <= 300 * 1024, '主视觉压缩后不应超过 300KB');
  // 解析 JPEG SOF 段取像素尺寸（PNG 的 IHDR 读法不再适用）
  let width = 0, height = 0;
  for (let i = 2; i < scene.length - 9;) {
    if (scene[i] !== 0xFF) { i += 1; continue; }
    const marker = scene[i + 1];
    if (marker >= 0xC0 && marker <= 0xCF && marker !== 0xC4 && marker !== 0xC8 && marker !== 0xCC) {
      height = scene.readUInt16BE(i + 5);
      width = scene.readUInt16BE(i + 7);
      break;
    }
    i += 2 + scene.readUInt16BE(i + 2);
  }
  assert.ok(width >= 1000 && height >= 780, '主视觉应保留足够的手机高清展示尺寸');
  assert.ok(Math.abs(width / height - 702 / 548) < 0.02, '素材与主视觉容器比例应一致');
});

test('v12 资源目录总体积受控（包体积整改回归护栏）', () => {
  const dir = path.join(root, 'assets/v12');
  const total = fs.readdirSync(dir).reduce((sum, name) => sum + fs.statSync(path.join(dir, name)).size, 0);
  assert.ok(total <= 600 * 1024, 'assets/v12 总计应 ≤ 600KB，当前 ' + Math.round(total / 1024) + 'KB，请压缩或改走 CDN');
});

test('首页取消右上消息入口后，主视觉完整铺满容器', () => {
  const heroArt = styles.match(/\.hero-art\s*\{([^}]+)\}/)[1];
  assert.match(heroArt, /inset:\s*0;/);
  assert.match(heroArt, /width:\s*100%;/);
  assert.match(heroArt, /height:\s*100%;/);
  assert.doesNotMatch(styles, /\.hero::after/, '不应再为已取消的铃铛预留缺口');
});

test('精选户型保留效果图的近方形图片与独立信息区，接口数据按位覆盖内置占位', () => {
  assert.match(styles, /\.featured image\s*\{[^}]*height:\s*304rpx;/);
  // 卡片改为 wx:for 渲染（接口数据按位覆盖内置占位），单模板 + 两条内置默认数据
  assert.equal((markup.match(/class="featured-copy"/g) || []).length, 1);
  assert.match(markup, /wx:for="{{featuredCases}}"/);
  const script = fs.readFileSync(path.join(root, 'pages/home/index.js'), 'utf8');
  assert.match(script, /villa-classic\.jpg/);
  assert.match(script, /villa-modern\.jpg/);
  assert.match(script, /DEFAULT_FEATURED/);
});
