'use strict';

const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');

protectedPage({
  data: {
    allowReference: false, missing: true, submitting: false,
    note: '', validation: null, versionReady: false
  },
  onLoad() {
    const global = getApp().globalData;
    const ready = !!(global.projectId && global.resultVersionId);
    this.setData({ versionReady: ready });
    if (ready) this.validate();
    else wx.showToast({ title: '请先完成方案设计', icon: 'none' });
  },
  validate() {
    const self = this;
    const global = getApp().globalData;
    if (!global.resultVersionId) {
      this.setData({ validation: null, missing: true });
      return;
    }
    // 校核与提交同构：以将要提交的载荷为准（服务端按载荷判缺失项）
    const payload = {
      resultVersionId: String(global.resultVersionId),
      publicDisplayGranted: true,
      generationReferenceGranted: this.data.allowReference
    };
    api.validatePublication(global.projectId, payload).then(function (validation) {
      self.setData({
        validation: validation,
        missing: !validation || validation.valid !== true
      });
    }).catch(function (err) {
      self.setData({ missing: true });
      wx.showToast({ title: (err && err.msg) || '发布校核失败', icon: 'none' });
    });
  },
  toggleReference(e) { this.setData({ allowReference: e.detail.value }); },
  completeDescription() {
    const self = this;
    wx.showModal({
      title: '补充方案说明',
      editable: true,
      placeholderText: '如：新中式二层五居，适合三代同住',
      success: function (res) {
        if (!res.confirm) return;
        self.setData({ note: (res.content || '').trim() }, function () { self.validate(); });
      }
    });
  },
  submit() {
    if (this.data.missing || this.data.submitting || !this.data.versionReady) return;
    const self = this;
    const global = getApp().globalData;
    self.setData({ submitting: true });
    const idemKey = 'submission-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
    api.submitForPublication(global.projectId, {
      resultVersionId: String(global.resultVersionId),
      publicDisplayGranted: true,
      generationReferenceGranted: this.data.allowReference,
      note: this.data.note || undefined
    }, idemKey).then(function () {
      wx.showToast({ title: '已提交审核', icon: 'success' });
      setTimeout(function () { wx.navigateBack(); }, 1200);
    }).catch(function (err) {
      wx.showToast({ title: (err && err.msg) || '提交失败，请重试', icon: 'none' });
      self.setData({ submitting: false });
    });
  }
});
