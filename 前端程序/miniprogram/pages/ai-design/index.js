'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const config = require('../../utils/config');
const sha256 = require('../../utils/sha256');
const designInputs = require('../../utils/design-inputs');
const generationOptions = require('../../utils/generation-options');
const attempts = require('../../utils/generation-attempt');
const flatDefaults = generationOptions.selection('FLAT');
const NOTE_SUGGESTIONS = ['老人房设在一楼', '客餐厅朝南', '厨房设在一楼', '保留露台', '增加储物空间'];

const SKETCH_MAX_BYTES = 10 * 1024 * 1024;

function mimeFromPath(path) {
  const lower = (path || '').toLowerCase();
  if (lower.endsWith('.png')) return 'image/png';
  if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) return 'image/jpeg';
  return 'image/jpeg'; // 个别机型临时文件无后缀：按 jpeg 申报，真实类型由后端魔数校验兜底
}

protectedPage({
  data: {
    mode: 0, count: 2,
    faceWidth: '', depth: '',
    floorLabels: designInputs.floorLabels(),
    familyLabels: designInputs.familyLabels(),
    floorIndex: designInputs.indexOfFloor('两层'),
    familyIndex: designInputs.indexOfFamily('5室3厅2卫'),
    points: '—', creating: false, quoteReady: false, quoteLoading: false, quoteError: '',
    refCase: null, refCaseText: '', note: '', prompt: '', showNoteSuggestions: false,
    noteSuggestions: NOTE_SUGGESTIONS,
    sketchAssetId: null, sketchImage: '', resolution: flatDefaults.resolution, orientation: flatDefaults.orientation, outputPixels: flatDefaults.outputPixels,
    resolutions: generationOptions.RESOLUTIONS, orientations: generationOptions.orientations(flatDefaults.resolution)
  },
  onLoad(options) {
    try {
      const saved = attempts.read('initial-flat');
      if (saved && saved.payload && saved.payload.body) {
        const body = saved.payload.body, inputs = body.requirementInputs || {};
        const options = saved.payload.imageOptions;
        const refCase = body.sourceType === 'CASE_REFERENCE' ? { caseId: body.refCaseId } : null;
        this._recoveringAttempt = true;
        this._lastAttemptKey = saved.key;
        this._lastRefCaseId = refCase && String(refCase.caseId);
        this.setData({ mode: saved.payload.mode == null ? (inputs.faceWidthM != null ? 0 : 1) : saved.payload.mode, refCase: refCase, count: saved.payload.count,
          faceWidth: inputs.faceWidthM == null ? '' : String(inputs.faceWidthM), depth: inputs.depthM == null ? '' : String(inputs.depthM),
          floorIndex: designInputs.FLOOR_OPTIONS.findIndex(o => o.count === inputs.floorCount),
          familyIndex: designInputs.indexOfFamily(inputs.family), note: inputs.note || '', prompt: inputs.prompt || '',
          sketchAssetId: body.sketchAssetId || null, resolution: options.resolution, orientation: options.orientation,
          pendingGeneration: true });
      }
    } catch (error) { /* No authenticated persisted attempt. */ }
    if (options && options.caseId) return; // 由 onShow 统一读全局参考案例
  },
  onShow() {
    // A different entry may have accepted and cleared the shared operation while this
    // tab was cached. A fresh appearance starts a new design rather than retaining a
    // completed operation marker forever.
    if (this._lastAttemptKey && !attempts.read('initial-flat')) {
      this._lastAttemptKey = null;
      this._recoveringAttempt = false;
      this.setData({ creating: false, pendingGeneration: false });
    }
    const refCase = this._recoveringAttempt ? this.data.refCase : getApp().globalData.refCase;
    const patch = { refCase: refCase };
    if (refCase && String(refCase.caseId) !== this._lastRefCaseId) {
      const parts = [];
      if (refCase.buildingArea) parts.push(refCase.buildingArea + '㎡');
      if (refCase.floorCount) parts.push(refCase.floorCount + '层');
      patch.refCaseText = parts.join(' · ');
      // T15：案例尺寸预填输入框，用户仍可修改；换案例时强制刷新（避免残留上一案例值被当作用户输入）
      patch.faceWidth = refCase.faceWidth != null && refCase.faceWidth !== '' ? String(refCase.faceWidth) : '';
      patch.depth = refCase.depth != null && refCase.depth !== '' ? String(refCase.depth) : '';
      const floorCount = Number(refCase.floorCount);
      const floorIndex = designInputs.FLOOR_OPTIONS.findIndex(function (o) { return o.count === floorCount; });
      if (floorIndex >= 0) patch.floorIndex = floorIndex;
    }
    this._lastRefCaseId = refCase && String(refCase.caseId);
    this.setData(patch);
    this.refreshPoints();
    require('../../utils/generation-price').refresh(this, 'FLAT');
  },
  refreshPoints() {
    const self = this;
    api.getPointAccount().then(function (account) {
      self.setData({ points: account.availablePoints });
    }).catch(function () { /* 静默保留占位 */ });
  },
  selectMode(e) { this.setData({ mode: Number(e.currentTarget.dataset.index) }); },
  selectCount(e) { if (this.data.creating) return; this.setData({ count: Number(e.currentTarget.dataset.count) }); require('../../utils/generation-price').refresh(this, 'FLAT'); },
  selectResolution(e) {
    if (this.data.creating) return;
    const selected = generationOptions.selection('FLAT', { resolution: e.currentTarget.dataset.value, orientation: this.data.orientation });
    this.setData({ resolution: selected.resolution, orientation: selected.orientation, outputPixels: selected.outputPixels,
      orientations: generationOptions.orientations(selected.resolution) });
    require('../../utils/generation-price').refresh(this, 'FLAT');
  },
  selectOrientation(e) {
    if (this.data.creating) return;
    const selected = generationOptions.selection('FLAT', { resolution: this.data.resolution, orientation: e.currentTarget.dataset.value });
    this.setData({ orientation: selected.orientation, outputPixels: selected.outputPixels });
  },
  retryQuote() { require('../../utils/generation-price').refresh(this, 'FLAT'); },
  chooseReference() { wx.switchTab({ url: '/pages/library/index' }); },
  clearReference() { getApp().globalData.refCase = null; this.setData({ refCase: null, refCaseText: '' }); },
  onNoteInput(e) { this.setData({ note: e.detail.value }); },
  toggleNoteSuggestions() { this.setData({ showNoteSuggestions: !this.data.showNoteSuggestions }); },
  selectNoteSuggestion(e) {
    const suggestion = this.data.noteSuggestions[Number(e.currentTarget.dataset.index)];
    if (!suggestion) return;
    const current = this.data.note.trim();
    const next = current ? current + (/[，。；;]$/.test(current) ? '' : '，') + suggestion : suggestion;
    this.setData({ note: next.slice(0, 200), showNoteSuggestions: false });
  },
  onPromptInput(e) { this.setData({ prompt: e.detail.value }); },
  onFaceWidthInput(e) { this.setData({ faceWidth: e.detail.value }); },
  onDepthInput(e) { this.setData({ depth: e.detail.value }); },
  onFloorChange(e) { this.setData({ floorIndex: Number(e.detail.value) }); },
  onFamilyChange(e) { this.setData({ familyIndex: Number(e.detail.value) }); },

  // ---- 草图上传：选图 → 摘要 → 上传票据 → 直传 → 完成校验 ----
  chooseImage() {
    const self = this;
    wx.chooseMedia({
      count: 1, mediaType: ['image'], sizeType: ['compressed'],
      success: function (res) {
        const file = res.tempFiles && res.tempFiles[0];
        if (!file) return;
        self.uploadSketch(file);
      }
    });
  },
  uploadSketch(file) {
    const self = this;
    const mime = mimeFromPath(file.tempFilePath);
    if (!mime) { wx.showToast({ title: '仅支持 JPG/PNG 图片', icon: 'none' }); return; }
    if (file.size > SKETCH_MAX_BYTES) { wx.showToast({ title: '图片不能超过10MB', icon: 'none' }); return; }
    wx.showLoading({ title: '上传中', mask: true });
    wx.getFileSystemManager().readFile({
      filePath: file.tempFilePath,
      success: function (res) {
        api.getUploadTicket('USER_SKETCH', mime, file.size, sha256.sha256Hex(res.data))
          .then(function (ticket) {
            return self.putObject(ticket, file.tempFilePath, res.data, mime).then(function () {
              return api.completeUpload(ticket.assetId);
            }).then(function (accepted) {
              if (!accepted) throw { msg: '图片校验未通过，请更换图片' };
              return ticket;
            });
          })
          .then(function (ticket) {
            wx.hideLoading();
            self.setData({ sketchAssetId: ticket.assetId, sketchImage: file.tempFilePath });
          })
          .catch(function (err) {
            wx.hideLoading();
            wx.showToast({ title: (err && err.msg) || '上传失败，请重试', icon: 'none' });
          });
      },
      fail: function () {
        wx.hideLoading();
        wx.showToast({ title: '读取图片失败', icon: 'none' });
      }
    });
  },
  // COS 直传分流（与 utils/avatar-upload.js 一致）：
  //  · https:// 预签名地址 → wx.request PUT 发二进制体；签名对象 PUT 绝不能带应用 Bearer（会破坏签名/越权），
  //    Content-Type 必须与申请票据时申报的 mime 一致，否则与后端签名不匹配被拒。
  //  · local:// 开发期地址 → wx.uploadFile multipart POST 到字节端点，带 Bearer + tenant-id（Content-Type 由 wx 自动置 multipart 边界）。
  putObject(ticket, filePath, data, mime) {
    const uploadUrl = ticket.uploadUrl || '';
    return new Promise(function (resolve, reject) {
      const success = function (res) {
        if (res.statusCode < 200 || res.statusCode >= 300) reject({ msg: '上传失败(' + res.statusCode + ')' });
        else resolve();
      };
      const fail = function () { reject({ msg: '上传失败，请检查网络' }); };
      if (/^https:\/\//.test(uploadUrl)) {
        wx.request({ url: uploadUrl, method: 'PUT', data: data, header: { 'Content-Type': mime }, success: success, fail: fail });
      } else if (/^local:\/\//.test(uploadUrl)) {
        wx.uploadFile({
          url: config.apiBase + api.assetContentUrl(ticket.assetId),
          filePath: filePath,
          name: 'file',
          header: { 'Authorization': 'Bearer ' + http.getToken(), 'tenant-id': String(config.tenantId) },
          success: success, fail: fail
        });
      } else {
        reject({ msg: '上传地址不可用' });
      }
    });
  },

  generate() {
    if (this.data.creating || !this.data.quoteReady) return;
    if (this._lastAttemptKey && !attempts.read('initial-flat')) {
      this.setData({ pendingGeneration: false });
      wx.showToast({ title: '上次任务已由其他入口受理，请到我的方案查看', icon: 'none' });
      return;
    }
    const count = this.data.count;
    const imageOptions = generationOptions.selection('FLAT', this.data);
    const self = this;
    self.setData({ creating: true });
    let attempt; const slot = 'initial-flat';
    const refCase = this.data.refCase;
    // T15：两种模式都全量上送需求输入（面宽/进深/层数/家庭需求/补充需求等），后端冻结进需求快照供 AI 提示词使用
    const built = designInputs.buildRequirementInputs(this.data.mode, this.data);
    if (built.error) {
      wx.showToast({ title: built.error, icon: 'none' });
      self.setData({ creating: false });
      return;
    }
    const body = this.data.mode === 0 && refCase && refCase.caseId
      ? { sourceType: 'CASE_REFERENCE', refCaseId: String(refCase.caseId), requirementInputs: built.inputs }
      : {
          sourceType: 'SELF_UPLOAD',
          sketchAssetId: this.data.sketchAssetId || undefined,
          requirementInputs: built.inputs
        };
    try { attempt = attempts.begin(slot, { body: body, mode: this.data.mode, count: count, imageOptions: { resolution: imageOptions.resolution, orientation: imageOptions.orientation } }); }
    catch (error) { self.setData({ creating: false }); wx.showToast({ title: error.msg || '无法保存生成请求', icon: 'none' }); return; }
    this._lastAttemptKey = attempt.key;
    let confirmedPrice;
    const priceRequest = attempt.confirmedPrice ? Promise.resolve(attempt.confirmedPrice)
      : require('../../utils/generation-price').confirm('FLAT', count, '', { resolution: imageOptions.resolution });
    return priceRequest.then(function (price) {
      confirmedPrice = price;
      attempt.confirmedPrice = price; attempts.save(slot, attempt);
      if (attempt.projectId) return { projectId: attempt.projectId };
      attempts.submitted(slot, attempt);
      return api.createProject(body, attempt.key);
    }).then(function (project) {
      attempts.assertCurrent(attempt);
      const projectId = project && project.projectId;
      if (!projectId) throw { msg: '创建设计项目失败' };
      attempt.projectId = projectId; attempts.save(slot, attempt);
      getApp().globalData.projectId = projectId;
      getApp().globalData.resultVersionId = null;
      attempts.submitted(slot, attempt);
      return api.createFlatJob(projectId, count, attempt.key, confirmedPrice,
        { resolution: imageOptions.resolution, orientation: imageOptions.orientation }).then(function (job) {
        attempts.assertCurrent(attempt);
        attempts.complete(slot, attempt);
        self._lastAttemptKey = null;
        self._recoveringAttempt = false; self.setData({ pendingGeneration: false });
        getApp().globalData.refCase = null;
        getApp().globalData.jobId = job.jobId;
        wx.redirectTo({ url: '/pages/ai-design/generating?stage=plane&count=' + count });
      });
    }).catch(function (err) {
      try { if (attempts.failed(slot, attempt, err)) self._lastAttemptKey = null; } catch (stale) { /* Do not modify another session. */ }
      if (!err || !err.cancelled) wx.showToast({ title: (err && err.msg) || '创建任务失败', icon: 'none' });
      let pending = false; try { pending = !!attempts.read(slot); } catch (unavailable) { /* Logged out. */ }
      self.setData({ creating: false, pendingGeneration: pending });
    });
  }
});
