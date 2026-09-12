"use strict";
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const STATUS_TEXT = { QUEUED: '等待生成', RUNNING: '方案生成中', VALIDATING: '结果审核中', SETTLING: '正在结算', CANCEL_REQUESTED: '取消处理中', SUCCEEDED: '生成完成', PARTIALLY_SUCCEEDED: '部分方案生成成功', FAILED: '生成失败', CANCELLED: '任务已取消' };

protectedPage({
  data: { stage: 'plane', count: 2, progress: 0, status: 'QUEUED',
          statusText: '正在读取任务', acceptedCount: 0, canCancel: false, cancelling: false, finished: false, pollError: '' },
  onLoad(query) {
    this._token = http.getToken();
    this._jobId = getApp().globalData.jobId;
    const stage = query.stage === 'elevation' ? 'elevation' : 'plane';
    const count = Number(query.count) || 2;
    this.setData({ stage: stage, count: count,
      tipTitle: stage === 'plane' ? '平面方案生成中' : '立面方案生成中',
      tipSubtitle: '共' + count + '个方案',
      jobId: this._jobId || '' });
    this.poll();
  },
  onUnload() {
    this._stopped = true;
    if (this._timer) clearTimeout(this._timer);
  },
  poll() {
    if (this._stopped) return;
    const self = this;
    const jobId = this._jobId;
    if (!jobId) { wx.navigateBack(); return; }
    return api.getAiJob(jobId).then(function (job) {
      if (self._stopped || !http.isSameSession(self._token)) return;
      if (!job || String(job.jobId) !== String(jobId)) throw Error('任务信息不一致');
      self._projectId = job.projectId;
      self.setData({ progress: Math.max(0, Math.min(100, Number(job.progress) || 0)), status: job.status,
        stage: job.phase === 'ELEVATION' ? 'elevation' : 'plane', count: job.requestedCount,
        acceptedCount: job.acceptedCount || 0, statusText: STATUS_TEXT[job.status] || '状态待确认',
        canCancel: (job.allowedActions || []).includes('CANCEL'),
        finished: ['SUCCEEDED', 'PARTIALLY_SUCCEEDED'].includes(job.status), pollError: '' });
      if (job.status === 'SUCCEEDED' || job.status === 'PARTIALLY_SUCCEEDED') {
        self.toCandidatePage();
      } else if (job.status === 'FAILED' || job.status === 'CANCELLED') {
        wx.showToast({ title: job.status === 'CANCELLED' ? '任务已取消' : '生成失败，点数将退回', icon: 'none' });
        self._timer = setTimeout(function () { wx.navigateBack(); }, 2000);
      } else {
        self._timer = setTimeout(function () { self.poll(); }, 2000);
      }
    }).catch(function () {
      if (self._stopped || !http.isSameSession(self._token)) return;
      self.setData({ pollError: '暂未取得最新进度，正在重试。' });
      self._timer = setTimeout(function () { self.poll(); }, 3000);
    });
  },
  toCandidatePage() {
    const projectId = this._projectId;
    if (!projectId || !http.isSameSession(this._token)) return;
    const target = this.data.stage === 'plane'
      ? '/pages/ai-design/plane-select?projectId=' + projectId
      : '/pages/ai-design/elevation-select?projectId=' + projectId;
    wx.redirectTo({ url: target });
  },
  cancel() {
    if (this._cancelling) return;
    const jobId = this._jobId;
    if (!jobId) return;
    this._cancelling = true;
    this.setData({ cancelling: true });
    const self = this;
    return api.cancelJob(jobId).then(function () {
      if (self._stopped || !http.isSameSession(self._token)) return;
      wx.showToast({ title: '取消请求已提交', icon: 'none' });
    }).catch(function (err) {
      self._cancelling = false;
      if (self._stopped || !http.isSameSession(self._token)) return;
      self.setData({ cancelling: false });
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
