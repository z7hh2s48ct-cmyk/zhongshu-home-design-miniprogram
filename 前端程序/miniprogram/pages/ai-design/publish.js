'use strict';

const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const view = require('../../utils/record-view');
const assets = require('../../utils/assets');

protectedPage({
  data: {
    allowReference: false, missing: true, submitting: false,
    note: '', validation: null, versionReady: false, allowPublicDisplay: false, coverUrl: '', materialCount: 0, versionNumber: ''
  },
  onLoad() {
    const global = getApp().globalData;
    this._projectId = view.id(global.projectId); this._versionId = view.id(global.resultVersionId);
    this._token = http.getToken(); this._closed = false;
    const ready = !!(this._projectId && this._versionId && this._token);
    if (ready) this.loadVersion();
    else wx.showToast({ title: '请先完成方案设计', icon: 'none' });
  },
  onUnload() { this._closed = true; },
  current() { return !this._closed && !!this._token && (http.isSameSession ? http.isSameSession(this._token) : this._token === http.getToken()); },
  loadVersion() {
    return api.getResultVersions(this._projectId).then(page => {
      if (!this.current()) return;
      const version = (page.list || []).find(item => String(item.versionId) === this._versionId && item.superseded === false);
      if (!version) throw Error('本版本不可投稿，请重新选择当前方案');
      const materialCount = [version.selectedFlatAssetId, version.selectedElevationAssetId].filter(view.id).length;
      this.setData({ versionReady: materialCount === 2, versionNumber: version.version, materialCount });
      assets.fetchProfileAvatar(version.selectedElevationAssetId).then(url => { if (this.current()) this.setData({ coverUrl: url }); }).catch(() => {});
      return this.validate();
    }).catch(error => { if (this.current()) { this.setData({ versionReady: false, missing: true }); wx.showToast({ title: error.message || '版本读取失败', icon: 'none' }); } });
  },
  validate() {
    const self = this;
    if (!this.current() || !this._versionId) {
      this.setData({ validation: null, missing: true });
      return;
    }
    // 校核与提交同构：以将要提交的载荷为准（服务端按载荷判缺失项）
    const payload = {
      resultVersionId: this._versionId,
      publicDisplayGranted: this.data.allowPublicDisplay,
      generationReferenceGranted: this.data.allowReference,
      note: this.data.note || undefined
    };
    const seq = this._validationSeq = (this._validationSeq || 0) + 1;
    this.setData({ missing: true });
    return api.validatePublication(this._projectId, payload).then(function (validation) {
      if (!self.current() || seq !== self._validationSeq) return;
      self.setData({
        validation: validation,
        missing: !self.data.allowPublicDisplay || !validation || validation.valid !== true
      });
    }).catch(function (err) {
      if (!self.current() || seq !== self._validationSeq) return;
      self.setData({ missing: true });
      wx.showToast({ title: (err && err.msg) || '发布校核失败', icon: 'none' });
    });
  },
  toggleReference(e) { if (!this.data.submitting) { this.setData({ allowReference: e.detail.value }); return this.validate(); } },
  togglePublicDisplay(e) { if (!this.data.submitting) { this.setData({ allowPublicDisplay: e.detail.value }); return this.validate(); } },
  completeDescription() {
    const self = this;
    wx.showModal({
      title: '补充方案说明',
      editable: true,
      placeholderText: '如：新中式二层五居，适合三代同住',
      success: function (res) {
        if (!res.confirm || !self.current() || self.data.submitting) return;
        self.setData({ note: (res.content || '').trim() }, function () { self.validate(); });
      }
    });
  },
  submit() {
    if (!this.current() || this.data.missing || this.data.submitting || !this.data.versionReady || !this.data.allowPublicDisplay) return;
    const self = this;
    self.setData({ submitting: true });
    const payload = {
      resultVersionId: this._versionId,
      publicDisplayGranted: this.data.allowPublicDisplay,
      generationReferenceGranted: this.data.allowReference,
      note: this.data.note || undefined
    };
    const signature = JSON.stringify(payload);
    if (signature !== this._submissionBody) { this._submissionBody = signature; this._submissionKey = 'submission-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10); }
    return api.submitForPublication(this._projectId, payload, this._submissionKey).then(function (saved) {
      if (!self.current()) return;
      if (!saved || !view.id(saved.submissionId)) throw Error('投稿未确认保存，请重试');
      wx.showToast({ title: '已提交审核', icon: 'success' });
      wx.redirectTo({ url: '/pages/profile/record?type=submissions&id=' + saved.submissionId,
        fail: function () { self.setData({ submitting: false }); wx.showToast({ title: '投稿已保存，可在「我的投稿」查看', icon: 'none' }); } });
    }).catch(function (err) {
      if (!self.current()) return;
      wx.showToast({ title: (err && (err.msg || err.message)) || '提交失败，请重试', icon: 'none' });
      self.setData({ submitting: false });
    });
  }
});
