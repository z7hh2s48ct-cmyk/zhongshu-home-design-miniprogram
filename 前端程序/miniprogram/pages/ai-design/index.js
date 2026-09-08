'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const config = require('../../utils/config');
const sha256 = require('../../utils/sha256');

const SKETCH_MAX_BYTES = 10 * 1024 * 1024;

function mimeFromPath(path) {
  const lower = (path || '').toLowerCase();
  if (lower.endsWith('.png')) return 'image/png';
  if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) return 'image/jpeg';
  return 'image/jpeg'; // 个别机型临时文件无后缀：按 jpeg 申报，真实类型由后端魔数校验兜底
}

protectedPage({
  data: {
    mode: 0, count: 2, floor: '两层', family: '5室3厅2卫',
    points: '—', creating: false,
    refCase: null, note: '', prompt: '',
    sketchAssetId: null, sketchImage: ''
  },
  onLoad(options) {
    if (options && options.caseId) return; // 由 onShow 统一读全局参考案例
  },
  onShow() {
    this.setData({ refCase: getApp().globalData.refCase });
    this.refreshPoints();
  },
  refreshPoints() {
    const self = this;
    api.getPointAccount().then(function (account) {
      self.setData({ points: account.availablePoints });
    }).catch(function () { /* 静默保留占位 */ });
  },
  selectMode(e) { this.setData({ mode: Number(e.currentTarget.dataset.index) }); },
  selectCount(e) { this.setData({ count: Number(e.currentTarget.dataset.count) }); },
  chooseReference() { wx.switchTab({ url: '/pages/library/index' }); },
  clearReference() { getApp().globalData.refCase = null; this.setData({ refCase: null }); },
  onNoteInput(e) { this.setData({ note: e.detail.value }); },
  onPromptInput(e) { this.setData({ prompt: e.detail.value }); },

  // ---- 草图上传：选图 → 摘要 → 上传票据 → 直传 → 完成校验 ----
  chooseImage(e) {
    const kind = e.currentTarget.dataset.kind === 'reference' ? 'reference' : 'sketch';
    const self = this;
    wx.chooseMedia({
      count: 1, mediaType: ['image'], sizeType: ['compressed'],
      success: function (res) {
        const file = res.tempFiles && res.tempFiles[0];
        if (!file) return;
        if (kind === 'reference') {
          // 后端创建项目契约暂无参考图字段，入口保留但暂不开放上传
          wx.showToast({ title: '参考图上传即将开放', icon: 'none' });
          return;
        }
        self.uploadSketch(file, kind);
      }
    });
  },
  uploadSketch(file, kind) {
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
            return self.putObject(ticket, file.tempFilePath, mime).then(function () {
              return api.completeUpload(ticket.assetId);
            }).then(function (accepted) {
              if (!accepted) throw { msg: '图片校验未通过，请更换图片' };
              return ticket;
            });
          })
          .then(function (ticket) {
            wx.hideLoading();
            if (kind === 'sketch') self.setData({ sketchAssetId: ticket.assetId, sketchImage: file.tempFilePath });
            else wx.showToast({ title: '参考图已上传', icon: 'success' });
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
  // COS 凭据到位前 uploadUrl 是 local:// 内部地址，走开发期直传端点（multipart POST）。
  // 注意：wx.uploadFile 只支持 multipart POST，切 COS 时预签名 PUT 需改用 wx.request 二进制体，届时一并处理
  putObject(ticket, filePath, mime) {
    const isHttpUrl = /^https?:\/\//.test(ticket.uploadUrl || '');
    const url = isHttpUrl ? ticket.uploadUrl
      : config.apiBase + '/app-api/design/v1/assets/' + ticket.assetId + '/content';
    return new Promise(function (resolve, reject) {
      wx.uploadFile({
        url: url,
        filePath: filePath,
        name: 'file',
        header: { 'Content-Type': mime, 'Authorization': 'Bearer ' + http.getToken() },
        success: function (res) {
          if (res.statusCode >= 400) reject({ msg: '上传失败(' + res.statusCode + ')' });
          else resolve();
        },
        fail: function () { reject({ msg: '上传失败，请检查网络' }); }
      });
    });
  },

  generate() {
    if (this.data.creating) return;
    const self = this;
    self.setData({ creating: true });
    const idemKey = 'proj-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
    const refCase = this.data.refCase;
    const body = refCase && refCase.caseId
      ? { sourceType: 'CASE_REFERENCE', refCaseId: String(refCase.caseId) }
      : {
          sourceType: 'SELF_UPLOAD',
          sketchAssetId: this.data.sketchAssetId || undefined,
          requirementInputs: {
            floor: this.data.floor, family: this.data.family,
            prompt: this.data.prompt, note: this.data.note
          }
        };
    api.createProject(body).then(function (project) {
      const projectId = project && project.projectId;
      if (!projectId) throw { msg: '创建设计项目失败' };
      getApp().globalData.projectId = projectId;
      getApp().globalData.refCase = null;
      getApp().globalData.resultVersionId = null;
      return api.createFlatJob(projectId, self.data.count, idemKey).then(function (job) {
        getApp().globalData.jobId = job.jobId;
        wx.redirectTo({ url: '/pages/ai-design/generating?stage=plane&count=' + self.data.count });
      });
    }).catch(function (err) {
      wx.showToast({ title: (err && err.msg) || '创建任务失败', icon: 'none' });
      self.setData({ creating: false });
    });
  }
});
