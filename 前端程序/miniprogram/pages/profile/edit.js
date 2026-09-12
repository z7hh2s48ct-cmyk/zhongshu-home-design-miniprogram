'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const assets = require('../../utils/assets');
const { uploadAvatar } = require('../../utils/avatar-upload');

protectedPage({
  data: { loading: true, saving: false, nickname: '', avatar: '', avatarPath: '', error: '' },
  onLoad() { this.load(); },
  onUnload() { this._closed = true; },
  load() {
    const token = this._token = http.getToken();
    this.setData({ loading: true, error: '' });
    return api.getProfile().then(p => {
      if (this._closed || !(http.isSameSession ? http.isSameSession(token) : token === http.getToken())) return;
      this.setData({ nickname: p.nickname || '', avatar: p.avatar || '', loading: false });
      if (p.avatarAssetId) assets.fetchProfileAvatar(p.avatarAssetId).then(avatar => {
        if (!this._closed && (http.isSameSession ? http.isSameSession(token) : token === http.getToken()) && !this.data.avatarPath) this.setData({ avatar });
      }).catch(() => {});
    }).catch(e => {
      if (!this._closed && (http.isSameSession ? http.isSameSession(token) : token === http.getToken())) this.setData({ loading: false, error: e.msg || e.message || '资料读取失败，请重试' });
    });
  },
  chooseAvatar(e) {
    if (this.data.saving || !e.detail.avatarUrl) return;
    this._uploadedId = null;
    this.setData({ avatarPath: e.detail.avatarUrl, avatar: e.detail.avatarUrl, error: '' });
  },
  changeNickname(e) { this._nicknameRejected = false; this.setData({ nickname: e.detail.value, error: '' }); },
  reviewNickname(e) {
    this._nicknameRejected = e.detail.pass === false;
    if (this._nicknameRejected) this.setData({ error: '昵称未通过微信校验，请修改后重试' });
  },
  async save(e) {
    if (this.data.loading || this.data.saving) return;
    const name = String(e.detail.value.nickname || '').trim();
    if (!name || name.length > 32 || /[\u0000-\u001f\u007f-\u009f]/.test(name)) {
      this.setData({ error: '昵称须为1至32个字符，不能包含控制字符' }); return;
    }
    if (this._nicknameRejected) { this.setData({ error: '昵称未通过微信校验，请修改后重试' }); return; }
    const token = this._token;
    const current = () => !this._closed && token && (http.isSameSession ? http.isSameSession(token) : token === http.getToken());
    if (!current()) { this.setData({ error: '登录身份已变化，请返回重新打开' }); return; }
    this.setData({ saving: true, error: '' });
    try {
      if (this.data.avatarPath && !this._uploadedId) this._uploadedId = await uploadAvatar(this.data.avatarPath);
      if (!current()) return;
      const saved = await api.updateProfile(name, this._uploadedId);
      if (!current()) return;
      if (saved !== true) throw Error('资料未保存，请重试');
      wx.showToast({ title: '资料已保存', icon: 'success' });
      wx.navigateBack({ fail: () => wx.switchTab({ url: '/pages/profile/index' }) });
    } catch (error) {
      if (current()) this.setData({ error: error.msg || error.message || '保存失败，请重试' });
    } finally {
      if (!this._closed) this.setData({ saving: false });
    }
  }
});
