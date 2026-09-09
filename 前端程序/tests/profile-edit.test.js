const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../miniprogram');
function environment(overrides = {}) {
  let definition;
  const state = { token: 'session-A', calls: [] };
  const api = Object.assign({
    getProfile: async () => ({ nickname: '原昵称', avatarAssetId: '11' }),
    updateProfile: async (...args) => { state.calls.push(['update', ...args]); return true; }
  }, overrides);
  const deps = {
    '../../utils/access': { protectedPage: value => { definition = value; } },
    '../../utils/api': api,
    '../../utils/request': { getToken: () => state.token },
    '../../utils/assets': { fetchProfileAvatar: async () => 'https://example.test/avatar' },
    '../../utils/avatar-upload': { uploadAvatar: async file => { state.calls.push(['upload', file]); return '22'; } }
  };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'pages/profile/edit.js'), 'utf8'), {
    require: name => deps[name], wx: { showToast: p => state.calls.push(['toast', p.title]), navigateBack: () => state.calls.push(['back']) }
  });
  const page = { ...definition, data: { ...definition.data }, setData(value) { Object.assign(this.data, value); } };
  return { page, state, api, submit: nickname => page.save({ detail: { value: { nickname } } }) };
}

test('我的首页按创作、账户、服务分类直接展示，只有设置详情进入二级页', () => {
  const index = fs.readFileSync(path.join(root, 'pages/profile/index.wxml'), 'utf8');
  const services = fs.readFileSync(path.join(root, 'pages/profile/services.wxml'), 'utf8');
  const edit = fs.readFileSync(path.join(root, 'pages/profile/edit.wxml'), 'utf8');
  for (const label of ['我的创作', '设计点账户', '消息与服务', '户型投稿', '收藏户型', '充值记录', '联系客服']) {
    assert.ok(index.includes(label));
  }
  assert.ok(!index.includes('授权信息')); assert.ok(services.includes('授权信息'));
  assert.ok(!services.includes('收藏户型'));
  assert.match(index, /openEdit/);
  assert.match(edit, /open-type="chooseAvatar"/);
  assert.match(edit, /type="nickname"/);
  assert.match(edit, /form-type="submit"/);
});

test('昵称按表单最新值保存，纯昵称修改不上传图片', async () => {
  const e = environment(); await e.page.load();
  await e.submit('  新昵称  ');
  assert.deepEqual(e.state.calls[0], ['update', '新昵称', undefined]);
  assert.equal(e.state.calls.at(-1)[0], 'back');
});

test('头像选择不立即上传，保存失败保留草稿，重试复用已校验资产', async () => {
  const e = environment(); await e.page.load();
  e.page.chooseAvatar({ detail: { avatarUrl: 'wxfile://chosen.png' } });
  assert.equal(e.state.calls.length, 0);
  e.api.updateProfile = async () => { throw Error('断网'); };
  await e.submit('头像用户');
  assert.equal(e.page.data.error, '断网');
  assert.equal(e.page.data.avatarPath, 'wxfile://chosen.png');
  assert.equal(e.page.data.saving, false);
  e.api.updateProfile = async (name, asset) => { assert.equal(asset, '22'); return true; };
  await e.submit('头像用户');
  assert.equal(e.state.calls.filter(c => c[0] === 'upload').length, 1);
  assert.equal(e.state.calls.at(-1)[0], 'back');
});

test('非法昵称与微信校验失败不保存，身份改变或页面关闭不接受旧响应', async () => {
  const e = environment(); await e.page.load();
  for (const value of ['', ' ', 'x'.repeat(33), 'bad\nname']) await e.submit(value);
  e.page.reviewNickname({ detail: { pass: false } }); await e.submit('昵称');
  assert.equal(e.state.calls.length, 0);
  e.page.changeNickname({ detail: { value: '改过' } });
  e.state.token = 'session-B'; await e.submit('改过');
  assert.equal(e.state.calls.length, 0);
  let resolve; const f = environment({ getProfile: () => new Promise(r => { resolve = r; }) });
  const pending = f.page.load(); f.page.onUnload(); resolve({ nickname: '迟到' }); await pending;
  assert.equal(f.page.data.nickname, '');
});

test('资料保存未确认成功时不显示成功、不返回，重复点击只有一次请求', async () => {
  let resolve; let calls = 0;
  const e = environment({ updateProfile: () => { calls++; return new Promise(r => { resolve = r; }); } });
  await e.page.load(); const saving = e.submit('昵称'); await e.submit('昵称');
  assert.equal(calls, 1); resolve(false); await saving;
  assert.match(e.page.data.error, /未保存/);
  assert.equal(e.state.calls.length, 0);
});

test('头像上传校验大小类型、复用票据与SHA，外部签名PUT不携带应用令牌', async () => {
  const data = new Uint8Array([137, 80, 78, 71, 0, 0, 0, 0]).buffer;
  const calls = []; let content = data; let accepted = true;
  const module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'utils/avatar-upload.js'), 'utf8'), {
    module, Uint8Array,
    require: name => name === './api' ? {
      getUploadTicket: async (...args) => { calls.push(args); return { assetId: '123', uploadUrl: 'https://storage.test/signed' }; },
      completeUpload: async () => accepted
    } : name === './request' ? { getToken: () => 'private-token' } : { sha256Hex: () => 'hash' },
    wx: {
      getFileSystemManager: () => ({ readFile: p => p.success({ data: content }) }),
      request: p => { assert.equal(p.method, 'PUT'); assert.equal(p.header.Authorization, undefined); p.success({ statusCode: 200 }); }
    }
  });
  assert.equal(await module.exports.uploadAvatar('file'), '123');
  assert.equal(calls[0][0], 'USER_AVATAR');
  accepted = false; await assert.rejects(module.exports.uploadAvatar('file'), /安全校验/);
  content = new Uint8Array(2097153).buffer; await assert.rejects(module.exports.uploadAvatar('file'), /2MB/);
  content = new Uint8Array([1, 2, 3]).buffer; await assert.rejects(module.exports.uploadAvatar('file'), /JPG/);
});
