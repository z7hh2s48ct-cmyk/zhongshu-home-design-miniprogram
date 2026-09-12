const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
function setup(entry, failed = false) {
  const calls = [], module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/utils/support.js'), 'utf8'), {
    module, require: () => ({ getSupportEntry: () => failed ? Promise.reject(Error()) : Promise.resolve(entry) }),
    wx: Object.fromEntries(['showModal', 'showToast', 'showActionSheet', 'makePhoneCall', 'setClipboardData', 'openCustomerServiceChat'].map(key => [key, value => calls.push({ key, value })]))
  });
  return { support: module.exports, calls };
}
test('support is driven by configured phone and does not dial before user selection', async () => {
  const f = setup({ phone: '400-123-4567' }); await f.support.contact();
  assert.equal(f.calls[0].key, 'showActionSheet'); assert.equal(f.calls.length, 1);
  f.calls[0].value.success({ tapIndex: 0 }); assert.equal(f.calls[1].value.phoneNumber, '4001234567');
});
test('wecom needs both corporate identity and official session address', async () => {
  const f = setup({ wecomCorpId: 'wwtestcorp', wecomUrl: 'https://work.weixin.qq.com/kfid/test' });
  await f.support.contact(); f.calls[0].value.success({ tapIndex: 0 });
  assert.equal(f.calls[1].key, 'openCustomerServiceChat'); assert.equal(f.calls[1].value.extInfo.url, 'https://work.weixin.qq.com/kfid/test');
  assert.equal(f.support.options({ wecomCorpId: 'wwtestcorp', wecomUrl: 'https://evil.example/kfid/test' }).length, 0);
});
test('missing, unsafe and unavailable support never show invented contact details', async () => {
  const f = setup({ phone: '<script>', helpUrl: 'javascript:alert(1)' }); await f.support.contact(); assert.equal(f.calls[0].key, 'showModal');
  const broken = setup({}, true); await broken.support.contact(); assert.equal(broken.calls[0].key, 'showToast');
});
