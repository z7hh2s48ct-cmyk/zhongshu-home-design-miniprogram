"use strict";
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const assets = require('../../utils/assets');
const generationOptions = require('../../utils/generation-options');
const flatDefaults = generationOptions.selection('FLAT');
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
          candidates: [], pendingSlots: 0, candidatesError: '',
          selectedId: '', selectDone: false, selecting: false, nextLabel: '', regenerating: false,
          resolution: flatDefaults.resolution, orientation: flatDefaults.orientation, outputPixels: flatDefaults.outputPixels },
  onLoad(query) {
    this._token = http.captureSession ? http.captureSession() : http.getToken();
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
      const jobOptions = generationOptions.selection(job.phase === 'ELEVATION' ? 'ELEVATION' : 'FLAT', job.imageOptions || job);
      const finished = ['SUCCEEDED', 'PARTIALLY_SUCCEEDED'].includes(job.status);
      const stepIndex = finished ? 3 : Math.max(0, STEP_ORDER.indexOf(job.status));
      self.setData({ status: job.status,
        stage: job.phase === 'ELEVATION' ? 'elevation' : 'plane', count: job.requestedCount,
        acceptedCount: job.acceptedCount || 0, statusText: STATUS_TEXT[job.status] || '状态待确认',
        canCancel: (job.allowedActions || []).includes('CANCEL'),
        stepIndex: stepIndex,
        finished: finished, pollError: '', resolution: jobOptions.resolution,
        orientation: jobOptions.orientation, outputPixels: jobOptions.outputPixels });
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
      const selected = generationOptions.selection(self.data.stage === 'plane' ? 'FLAT' : 'ELEVATION', project);
      const list = (project.candidates || [])
        .filter(function (c) { return String(c.jobId) === String(self._jobId); });
      const merged = self.data.candidates.slice();
      let added = false;
      list.forEach(function (c) {
        if (merged.some(function (item) { return String(item.candidateId) === String(c.candidateId); })) return;
        merged.push({ candidateId: String(c.candidateId), assetId: c.assetId,
          slot: c.slotNo || (merged.length + 1), name: '方案 ' + String.fromCharCode(65 + merged.length), url: '', imageError: '', imageLoading: false });
        added = true;
      });
      const pendingSlots = self.data.finished ? 0 : Math.max(0, (self.data.count || 0) - merged.length);
      self.setData({ candidates: merged, pendingSlots: pendingSlots, candidatesError: '',
        resolution: selected.resolution, orientation: selected.orientation, outputPixels: selected.outputPixels });

      merged.forEach(function (item) { if (!item.url && !item.imageLoading) self.loadCandidateImage(item.candidateId); });
    }).catch(function () {
      // 候选拉取失败不打断进度轮询，下轮重试；但若已到终态，必须给用户显式重试出口而不是空页面
      if (self._stopped || !self.current(self._token)) return;
      if (self.data.finished && !self.data.candidates.length) self.setData({ candidatesError: '候选读取失败，可重试加载' });
    });
  },
  loadCandidateImage(candidateId) {
    const index = this.data.candidates.findIndex(item => String(item.candidateId) === String(candidateId));
    if (index < 0) return;
    const item = this.data.candidates[index], jobId = this._jobId;
    if (!item.assetId || item.imageLoading) return;
    this.setData({ ['candidates[' + index + '].imageLoading']: true, ['candidates[' + index + '].imageError']: '' });
    const apply = patch => {
      if (this._stopped || !this.current(this._token) || jobId !== this._jobId) return;
      const target = this.data.candidates[index];
      if (!target || target.candidateId !== item.candidateId || target.assetId !== item.assetId) return;
      const update = {};
      Object.keys(patch).forEach(key => { update['candidates[' + index + '].' + key] = patch[key]; });
      this.setData(update);
    };
    return assets.fetchAssetDataUrl(item.assetId).then(url => apply({ url, imageLoading: false, imageError: '' }))
      .catch(() => apply({ url: '', imageLoading: false, imageError: '图片加载失败，点击重试' }));
  },
  retryCandidateImage(event) { return this.loadCandidateImage(event.currentTarget.dataset.id); },
  candidateImageError(event) {
    const index = this.data.candidates.findIndex(item => String(item.candidateId) === String(event.currentTarget.dataset.id));
    if (index >= 0) this.setData({ ['candidates[' + index + '].url']: '', ['candidates[' + index + '].imageLoading']: false,
      ['candidates[' + index + '].imageError']: '图片已失效，点击重新加载' });
  },
  current(token) { return http.isSameSession ? http.isSameSession(token) : token === http.getToken(); },
  // 页内选择：与主/恢复两路径共用（选择页已收敛到本页内联候选）
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
    // 带 projectId/resultVersionId 直达，不依赖 globalData（场景恢复后内存态可能为空）
    let url = NEXT[this.data.stage].url;
    if (this.data.stage === 'elevation' && this._projectId) {
      const versionId = getApp().globalData.resultVersionId;
      url += '?projectId=' + this._projectId + (versionId ? '&resultVersionId=' + versionId : '');
    }
    wx.redirectTo({ url: url, fail: () => wx.showToast({ title: '页面打开失败，请重试', icon: 'none' }) });
  },
  retryCandidates() {
    this.setData({ candidatesError: '' });
    this.refreshCandidates();
  },
  leavePage() {
    wx.navigateBack({ fail: () => wx.switchTab({ url: '/pages/ai-design/index' }) });
  },
  // 选择页收敛后重生成入口移到这里：不满意候选可直接再生成（扣点确认在前），逻辑与原两页一致
  regenerate() {
    if (!this.data.finished || this.data.selectDone || this._regenerating) return;
    const projectId = this._projectId;
    if (!projectId) { wx.showToast({ title: '项目信息未就绪，请重新进入', icon: 'none' }); return; }
    const stage = this.data.stage;
    if (stage === 'elevation' && !getApp().globalData.elevationConfig) {
      // 无可复用的立面配置：先去配置页（与原选择页行为一致）
      wx.navigateTo({ url: '/pages/ai-design/elevation-setup', fail: () => wx.showToast({ title: '页面打开失败，请重试', icon: 'none' }) });
      return;
    }
    this._regenerating = true;
    const self = this;
    const count = this.data.count || 2;
    const fallback = generationOptions.defaults(stage === 'plane' ? 'FLAT' : 'ELEVATION');
    const resolution = this.data.resolution || fallback.resolution;
    const orientation = this.data.orientation || fallback.orientation;
    const attempts = require('../../utils/generation-attempt');
    const slot = 'regenerate:' + projectId + ':' + this._jobId;
    const payload = { projectId: projectId, stage: stage, count: count, resolution: resolution, orientation: orientation,
      config: stage === 'elevation' ? Object.assign({}, getApp().globalData.elevationConfig) : null };
    if (payload.config) delete payload.config.priceConfirmation;
    let attempt;
    try { attempt = attempts.begin(slot, payload); }
    catch (error) { this._regenerating = false; wx.showToast({ title: error.msg || '无法保存请求', icon: 'none' }); return; }
    const idemKey = attempt.key;
    this.setData({ regenerating: true });
    const priceRequest = attempt.confirmedPrice ? Promise.resolve(attempt.confirmedPrice)
      : require('../../utils/generation-price').confirm(stage === 'plane' ? 'FLAT' : 'ELEVATION', count, '', { resolution: resolution });
    return priceRequest.then(function (price) {
      attempt.confirmedPrice = price; attempts.save(slot, attempt);
      attempts.submitted(slot, attempt);
      if (stage === 'plane') return api.createFlatJob(projectId, count, idemKey, price, { resolution: resolution, orientation: orientation });
      const config = Object.assign({}, getApp().globalData.elevationConfig, { count: count, resolution: resolution, orientation: orientation, priceConfirmation: price });
      return api.createElevationJob(projectId, config, idemKey);
    }).then(function (job) {
      if (self._stopped) return;
      attempts.complete(slot, attempt);
      getApp().globalData.jobId = job.jobId;
      wx.redirectTo({ url: '/pages/ai-design/generating?stage=' + stage + '&count=' + count });
    }).catch(function (err) {
      try { attempts.failed(slot, attempt, err); } catch (stale) { /* Session changed. */ }
      if (!err || !err.cancelled) wx.showToast({ title: (err && err.msg) || '重新生成失败', icon: 'none' });
      self._regenerating = false;
      self.setData({ regenerating: false });
    });
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
