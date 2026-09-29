"use strict";
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const assets = require('../../utils/assets');
const STATUS_TEXT = { QUEUED: '排队等待中', RUNNING: 'AI 正在绘制方案', VALIDATING: '正在校验生成结果', CANCEL_REQUESTED: '正在取消任务', SUCCEEDED: '生成完成', PARTIALLY_SUCCEEDED: '部分方案已生成', FAILED: '生成失败', CANCELLED: '任务已取消' };
const STEP_ORDER = ['QUEUED', 'RUNNING', 'VALIDATING'];
const NEXT = {
  plane: { label: '下一步：配置立面生成', url: '/pages/ai-design/elevation-setup' },
  elevation: { label: '查看最终方案', url: '/pages/ai-design/result' }
};

protectedPage({
  data: { stage: 'plane', count: 2, progress: 0, status: 'QUEUED',
          statusText: '正在读取任务', acceptedCount: 0, canCancel: false, cancelling: false, finished: false, pollError: '',
          stepIndex: 0, elapsedText: '00:00',
          candidates: [], pendingSlots: 0,
          selectedId: '', selectDone: false, selecting: false, nextLabel: '' },
  onLoad(query) {
    this._token = http.getToken();
    this._jobId = getApp().globalData.jobId;
    this._pageStart = Date.now();
    this._statusAt = {};
    const stage = query.stage === 'elevation' ? 'elevation' : 'plane';
    const count = Number(query.count) || 2;
    this.setData({ stage: stage, count: count,
      tipTitle: stage === 'plane' ? '平面方案生成中' : '立面方案生成中',
      tipSubtitle: '共' + count + '个方案' });
    this.startTicker();
    this.poll();
  },
  onUnload() {
    this._stopped = true;
    if (this._timer) clearTimeout(this._timer);
    if (this._uiTimer) clearInterval(this._uiTimer);
  },
  startTicker() {
    const self = this;
    this._uiTimer = setInterval(function () {
      if (self._stopped) return;
      self.setData({ progress: self.computeProgress(), elapsedText: self.elapsedText() });
    }, 500);
  },
  elapsedText() {
    const total = Math.max(0, Math.floor((Date.now() - this._pageStart) / 1000));
    const mm = String(Math.floor(total / 60)).padStart(2, '0'), ss = String(total % 60).padStart(2, '0');
    return mm + ':' + ss;
  },
  // 平滑进度：后端仅在单张图完成时回报一次进度，直接展示会长期停在 0%。
  // 前端按阶段推进平滑进度（渐近上限），后端回报值只用来抬高、不用来回落。
  computeProgress() {
    const self = this;
    const status = this.data.status;
    if (this.data.finished) return 100;
    if (status === 'FAILED' || status === 'CANCELLED') return this.data.progress;
    const now = Date.now();
    const since = function (key) { return (now - (self._statusAt[key] || self._pageStart)) / 1000; };
    let target;
    if (status === 'VALIDATING') target = 90 + Math.min(8, since('VALIDATING') / 2);
    else if (status === 'RUNNING') target = 14 + 76 * (1 - Math.exp(-since('RUNNING') / 45));
    else if (status === 'CANCEL_REQUESTED') target = this.data.progress;
    else target = Math.min(12, since('QUEUED') * 0.8);
    const server = Number(this._serverProgress) || 0;
    return Math.round(Math.max(Math.min(target, 98), Math.min(server, 96), 0));
  },
  poll() {
    if (this._stopped) return;
    const self = this;
    const jobId = this._jobId;
    if (!jobId) { wx.navigateBack(); return; }
    return api.getAiJob(jobId).then(function (job) {
      if (self._stopped || !http.isSameSession(self._token)) return;
      if (!job || String(job.jobId) !== String(jobId)) throw Error('任务信息不一致');
      if (!self._statusAt[job.status]) self._statusAt[job.status] = Date.now();
      self._serverProgress = Number(job.progress) || 0;
      self._projectId = job.projectId;
      const finished = ['SUCCEEDED', 'PARTIALLY_SUCCEEDED'].includes(job.status);
      const stepIndex = finished ? 3 : Math.max(0, STEP_ORDER.indexOf(job.status));
      self.setData({ status: job.status,
        stage: job.phase === 'ELEVATION' ? 'elevation' : 'plane', count: job.requestedCount,
        acceptedCount: job.acceptedCount || 0, statusText: STATUS_TEXT[job.status] || '状态待确认',
        canCancel: (job.allowedActions || []).includes('CANCEL'),
        stepIndex: stepIndex,
        finished: finished, pollError: '' });
      // 候选是拉式晋升的：有新通过校验的方案，或任务刚到终态，都拉一次候选并内联呈现
      if ((job.acceptedCount || 0) > (self._acceptedSeen || 0) || finished) {
        self._acceptedSeen = job.acceptedCount || 0;
        self.refreshCandidates();
      }
      if (finished) {
        // 改版：完成不再跳转选择页，候选已内联呈现，用户在本页点「选它」
        self.setData({ progress: 100 });
      } else if (job.status === 'FAILED' || job.status === 'CANCELLED') {
        wx.showToast({ title: job.status === 'CANCELLED' ? '任务已取消' : '生成失败，点数将退回', icon: 'none' });
        if (!self._leaving) {
          self._leaving = true;
          self._timer = setTimeout(function () { wx.navigateBack(); }, 2000);
        }
      } else {
        self._timer = setTimeout(function () { self.poll(); }, 2000);
      }
    }).catch(function () {
      if (self._stopped || !http.isSameSession(self._token)) return;
      self.setData({ pollError: '暂未取得最新进度，正在重试。' });
      self._timer = setTimeout(function () { self.poll(); }, 3000);
    });
  },
  // 拉取本任务候选（后端先晋升再下发），差量合并进候选区；未完成槽位渲染灰占位
  refreshCandidates() {
    const self = this;
    if (!this._projectId || !this._jobId) return;
    api.getProject(this._projectId, this._jobId).then(function (project) {
      if (self._stopped || !self.current(self._token)) return;
      const list = (project.candidates || [])
        .filter(function (c) { return String(c.jobId) === String(self._jobId); });
      const merged = self.data.candidates.slice();
      let added = false;
      list.forEach(function (c) {
        if (merged.some(function (item) { return String(item.candidateId) === String(c.candidateId); })) return;
        merged.push({ candidateId: String(c.candidateId), assetId: c.assetId,
          slot: c.slotNo || (merged.length + 1), name: '方案 ' + String.fromCharCode(65 + merged.length), url: '' });
        added = true;
      });
      const pendingSlots = self.data.finished ? 0 : Math.max(0, (self.data.count || 0) - merged.length);
      self.setData({ candidates: merged, pendingSlots: pendingSlots });
      if (!added) return;
      merged.forEach(function (item, index) {
        if (item.url || !item.assetId) return;
        assets.fetchAssetDataUrl(item.assetId).then(function (url) {
          if (self._stopped) return;
          const target = self.data.candidates[index];
          if (target && target.assetId === item.assetId && !target.url) {
            self.setData({ ['candidates[' + index + '].url']: url });
          }
        }).catch(function () { /* 单图加载失败保留占位 */ });
      });
    }).catch(function () { /* 候选拉取失败不打断进度轮询，下轮重试 */ });
  },
  current(token) { return http.isSameSession ? http.isSameSession(token) : token === http.getToken(); },
  // 页内选择：与 plane-select / elevation-select 完全同一接口与全局副作用，仅不再跳转
  selectCandidate(event) {
    if (!this.data.finished || this.data.selectDone || this.data.selecting) return;
    const candidateId = event.currentTarget.dataset.id;
    const cand = this.data.candidates.find(function (item) { return String(item.candidateId) === String(candidateId); });
    if (!cand) return;
    const self = this;
    this.setData({ selecting: true });
    const global = getApp().globalData;
    const request = this.data.stage === 'plane'
      ? api.selectFlat(this._projectId, this._jobId, cand.candidateId).then(function () {
          global.selectedCandidateId = cand.candidateId;
          global.selectedFlatAssetId = cand.assetId;
          global.flatLabel = cand.name;
          return null;
        })
      : api.selectElevation(this._projectId, this._jobId, cand.candidateId).then(function (vo) {
          global.selectedElevationId = cand.candidateId;
          global.selectedElevationAssetId = cand.assetId;
          global.elevationLabel = cand.name;
          global.resultVersionId = (vo && vo.resultVersionId) ? vo.resultVersionId : null;
          return null;
        });
    return request.then(function () {
      if (self._stopped || !self.current(self._token)) return;
      const next = NEXT[self.data.stage];
      self.setData({ selectedId: cand.candidateId, selectDone: true, selecting: false, nextLabel: next.label });
      wx.showToast({ title: '已选定 ' + cand.name, icon: 'success' });
    }).catch(function (err) {
      if (self._stopped || !self.current(self._token)) return;
      self.setData({ selecting: false });
      wx.showToast({ title: (err && err.msg) || '选定失败，请重试', icon: 'none' });
    });
  },
  nextStep() {
    if (!this.data.selectDone || !this.current(this._token)) return;
    wx.redirectTo({ url: NEXT[this.data.stage].url, fail: () => wx.showToast({ title: '页面打开失败，请重试', icon: 'none' }) });
  },
  cancel() {
    if (this._cancelling) return;
    const jobId = this._jobId;
    if (!jobId) return;
    this._cancelling = true;
    this.setData({ cancelling: true });
    const self = this;
    return api.cancelJob(jobId).then(function () {
      if (self._stopped || !self.current(self._token)) return;
      wx.showToast({ title: '取消请求已提交', icon: 'none' });
    }).catch(function (err) {
      self._cancelling = false;
      if (self._stopped || !self.current(self._token)) return;
      self.setData({ cancelling: false });
      wx.showToast({ title: (err && err.msg) || '取消失败', icon: 'none' });
    }).then(function () { /* 保留 _cancelling：取消请求已受理，无需重复提交 */ });
  },
  continueLater() {
    wx.showToast({ title: '任务将在后台继续生成', icon: 'none' });
    setTimeout(function () { wx.switchTab({ url: '/pages/home/index' }); }, 600);
  }
});
