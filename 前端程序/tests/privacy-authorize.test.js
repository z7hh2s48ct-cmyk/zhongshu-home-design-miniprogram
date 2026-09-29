const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../miniprogram/utils/privacy.js'), 'utf8');

// 只注入受控 wx 桩，验证隐私封装的四条契约：
// 探测准确、拉取准确、基础库缺 API 时保守放行、任何异常都不外抛。
function load(wx) {
  const module = { exports: {} };
  vm.runInNewContext(source, { module, exports: module.exports, wx, console }, { filename: 'privacy.js' });
  return module.exports;
}

test('needAuthorization 透传微信探测结果', async () => {
  assert.equal(await load({ getPrivacySetting: ({ success }) => success({ needAuthorization: true }) }).needAuthorization(), true);
  assert.equal(await load({ getPrivacySetting: ({ success }) => success({ needAuthorization: false }) }).needAuthorization(), false);
});

test('needAuthorization 在探测失败或抛错时保守返回 false', async () => {
  assert.equal(await load({ getPrivacySetting: ({ fail }) => fail({ errMsg: 'fail' }) }).needAuthorization(), false);
  assert.equal(await load({ getPrivacySetting: () => { throw new Error('基础库异常'); } }).needAuthorization(), false);
  assert.equal(await load({}).needAuthorization(), false);
  assert.equal(await load(undefined).needAuthorization(), false);
});

test('authorize 区分同意与拒绝', async () => {
  assert.equal(await load({ requirePrivacyAuthorize: ({ success }) => success() }).authorize(), true);
  assert.equal(await load({ requirePrivacyAuthorize: ({ fail }) => fail({ errMsg: 'deny' }) }).authorize(), false);
});

test('authorize 在缺少 API 或抛错时不锁死功能：缺 API 放行，抛错按未授权', async () => {
  assert.equal(await load({}).authorize(), true, '旧基础库缺 requirePrivacyAuthorize 时不应阻断使用');
  assert.equal(await load(undefined).authorize(), true);
  assert.equal(await load({ requirePrivacyAuthorize: () => { throw new Error('boom'); } }).authorize(), false);
});

test('ensure 仅在需要授权时才拉起弹窗', async () => {
  const calls = [];
  const needed = load({
    getPrivacySetting: ({ success }) => success({ needAuthorization: true }),
    requirePrivacyAuthorize: ({ success }) => { calls.push('authorize'); success(); },
  });
  assert.equal(await needed.ensure(), true);
  assert.deepEqual(calls, ['authorize']);

  const notNeeded = load({
    getPrivacySetting: ({ success }) => success({ needAuthorization: false }),
    requirePrivacyAuthorize: () => { calls.push('unexpected'); },
  });
  assert.equal(await notNeeded.ensure(), true);
  assert.deepEqual(calls, ['authorize'], '无需授权时不得弹出授权框');
});

test('ensure 对用户拒绝返回 false 但不抛错', async () => {
  const platform = load({
    getPrivacySetting: ({ success }) => success({ needAuthorization: true }),
    requirePrivacyAuthorize: ({ fail }) => fail({ errMsg: 'deny' }),
  });
  assert.equal(await platform.ensure(), false);
});
