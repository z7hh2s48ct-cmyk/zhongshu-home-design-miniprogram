const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const root = path.resolve(__dirname, '../miniprogram');
const markup = fs.readFileSync(path.join(root, 'pages/home/index.wxml'), 'utf8');
const styles = fs.readFileSync(path.join(root, 'pages/home/index.scss'), 'utf8');

test('首页使用接近主视觉容器比例的完整场景，避免窄图被再次放大裁切', () => {
  assert.match(markup, /class="hero-art" src="\/assets\/v12\/home-hero-scene\.png" mode="aspectFill"/);
  const scene = fs.readFileSync(path.join(root, 'assets/v12/home-hero-scene.png'));
  assert.equal(scene.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
  const width = scene.readUInt32BE(16);
  const height = scene.readUInt32BE(20);
  assert.ok(width >= 1000 && height >= 800, '主视觉应保留足够的手机高清展示尺寸');
  assert.ok(Math.abs(width / height - 702 / 548) < 0.02, '素材与主视觉容器比例应一致');
});

test('首页场景铺满容器，只在消息入口保留局部缺口', () => {
  const heroArt = styles.match(/\.hero-art\s*\{([^}]+)\}/)[1];
  assert.match(heroArt, /inset:\s*0;/);
  assert.match(heroArt, /width:\s*100%;/);
  assert.match(heroArt, /height:\s*100%;/);
  const notch = styles.match(/\.hero::after\s*\{([^}]+)\}/)[1];
  assert.match(notch, /width:\s*86rpx;/);
  assert.match(notch, /height:\s*64rpx;/);
  assert.doesNotMatch(notch, /gradient|inset/);
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
