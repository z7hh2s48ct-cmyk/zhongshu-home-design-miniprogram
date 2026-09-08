"use strict";
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');

protectedPage({
  data: { stage: 'plane', count: 2, progress: 0, status: 'QUEUED',
          tipTitle: '平面方案生成中', tipSubtitle: '', flatLabel: '' },
  onLoad(query) {
    const stage = query.stage === 'elevation' ? 'elevation' : 'plane';
    const count = Number(query.count) || 2;
    this.setData({ stage: stage, count: count,
      tipTitle: stage === 'plane' ? '平面方案生成中' : '立面方案生成中',
      tipSubtitle: '共' + count + '个方案',
      flatLabel: getApp().globalData.flatLabel || '' });
    this.poll();
  },
  onUnload() {
    this._stopped = true;
    if (this._timer) clearTimeout(this._timer);
  },
  poll() {
    if (this._stopped) return;
    const self = this;
    const jobId = getApp().globalData.jobId;
    if (!jobId) { wx.navigateBack(); return; }
    api.getAiJob(jobId).then(function (job) {
      if (self._stopped) return;
      self.setData({ progress: job.progress || 0, status: job.status });
      if (job.status === 'SUCCEEDED' || job.status === 'PARTIALLY_SUCCEEDED') {
        self.toCandidatePage();
      } else if (job.status === 'FAILED' || job.status === 'CANCELLED') {
        wx.showToast({ title: job.status === 'CANCELLED' ? '任务已取消' : '生成失败，点数将退回', icon: 'none' });
        self._timer = setTimeout(function () { wx.navigateBack(); }, 2000);
      } else {
        self._timer = setTimeout(function () { self.poll(); }, 2000);
      }
    }).catch(function () {
      if (self._stopped) return;
      self._timer = setTimeout(function () { self.poll(); }, 3000);
    });
  },
  toCandidatePage() {
    const projectId = getApp().globalData.projectId;
    const target = this.data.stage === 'plane'
      ? '/pages/ai-design/plane-select?projectId=' + projectId
      : '/pages/ai-design/elevation-select?projectId=' + projectId;
    wx.redirectTo({ url: target });
  },
  cancel() {
    if (this._cancelling) return;
    const jobId = getApp().globalData.jobId;
    if (!jobId) return;
    this._cancelling = true;
    api.cancelJob(jobId).then(function () {
      wx.showToast({ title: '取消请求已提交', icon: 'none' });
    }).catch(function (err) {
      wx.showToast({ title: (err && err.msg) || '取消失败', icon: 'none' });
    }).then(function () { /* 保留 _cancelling：取消请求已受理，无需重复提交 */ });
  },
  continueLater() {
    wx.showToast({ title: '任务将在后台继续生成', icon: 'none' });
    setTimeout(function () { wx.switchTab({ url: '/pages/home/index' }); }, 600);
  },
  previewResult() {
    if (this.data.status === 'SUCCEEDED' || this.data.status === 'PARTIALLY_SUCCEEDED') {
      this.toCandidatePage();
      return;
    }
    wx.showToast({ title: '生成完成后即可预览', icon: 'none' });
  }
});
