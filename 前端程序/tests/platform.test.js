const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../miniprogram/utils/platform.js'), 'utf8');

// 在隔离上下文里加载 platform.js，仅注入受控的 wx 桩：
// 目的是验证「新 API 优先 / 旧 API 回退 / API 缺失或抛错时保守按非 iOS」三条契约，
// 不触网、不依赖真实运行环境。
function load(wx) {
  const module = { exports: {} };
  vm.runInNewContext(source, { module, exports: module.exports, wx, console }, { filename: 'platform.js' });
  return module.exports;
}

test('优先使用 getDeviceInfo，且不触碰已废弃的 getSystemInfoSync', () => {
  const calls = [];
  const platform = load({
    getDeviceInfo: () => { calls.push('device'); return { platform: 'ios' }; },
    getSystemInfoSync: () => { calls.push('sysinfo'); return { platform: 'android' }; },
  });
  assert.equal(platform.currentPlatform(), 'ios');
  assert.equal(platform.isIOS(), true);
  assert.ok(calls.length >= 1 && calls.every(name => name === 'device'), '只应走新 API，回退路径不得被触发');
});

test('缺少 getDeviceInfo 时回退 getSystemInfoSync', () => {
  const platform = load({ getSystemInfoSync: () => ({ platform: 'android' }) });
  assert.equal(platform.currentPlatform(), 'android');
  assert.equal(platform.isIOS(), false);
});

test('平台取值为 iOS 时判定为真，且大小写不敏感', () => {
  const platform = load({ getDeviceInfo: () => ({ platform: 'iOS' }) });
  assert.equal(platform.isIOS(), true);
});

test('非微信环境（无 wx）保守按非 iOS，且不抛错', () => {
  const platform = load(undefined);
  assert.equal(platform.currentPlatform(), 'unknown');
  assert.equal(platform.isIOS(), false);
});

test('缺少全部平台 API 时保守按非 iOS', () => {
  const platform = load({});
  assert.equal(platform.currentPlatform(), 'unknown');
  assert.equal(platform.isIOS(), false);
});

test('取平台抛错时被吞掉并保守按非 iOS，不阻断充值页', () => {
  const platform = load({ getDeviceInfo: () => { throw new Error('基础库不支持'); } });
  assert.equal(platform.currentPlatform(), 'unknown');
  assert.equal(platform.isIOS(), false);
});

test('平台字段缺失或非字符串时不误判为 iOS', () => {
  assert.equal(load({ getDeviceInfo: () => ({}) }).isIOS(), false);
  assert.equal(load({ getDeviceInfo: () => ({ platform: 123 }) }).isIOS(), false);
  assert.equal(load({ getDeviceInfo: () => null }).isIOS(), false);
});
