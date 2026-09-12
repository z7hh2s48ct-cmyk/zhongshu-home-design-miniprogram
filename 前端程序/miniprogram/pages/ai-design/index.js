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
    require('../../utils/generation-price').refresh(this, 'FLAT');
  },
  refreshPoints() {
    const self = this;
    api.getPointAccount().then(function (account) {
      self.setData({ points: account.availablePoints });
    }).catch(function () { /* 静默保留占位 */ });
  },
  selectMode(e) { this.setData({ mode: Number(e.currentTarget.dataset.index) }); },
  selectCount(e) { this.setData({ count: Number(e.currentTarget.dataset.count) }); require('../../utils/generation-price').refresh(this, 'FLAT'); },
  chooseReference() { wx.switchTab({ url: '/pages/library/index' }); },
  clearReference() { getApp().globalData.refCase = null; this.setData({ refCase: null }); },
  onNoteInput(e) { this.setData({ note: e.detail.value }); },
  onPromptInput(e) { this.setData({ prompt: e.detail.value }); },

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
    if (this.data.creating) return;
    const count = this.data.count;
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
    let confirmedPrice;
    require('../../utils/generation-price').confirm('FLAT', count).then(function (price) {
      confirmedPrice = price;
      return api.createProject(body);
    }).then(function (project) {
      const projectId = project && project.projectId;
      if (!projectId) throw { msg: '创建设计项目失败' };
      getApp().globalData.projectId = projectId;
      getApp().globalData.refCase = null;
      getApp().globalData.resultVersionId = null;
      return api.createFlatJob(projectId, count, idemKey, confirmedPrice).then(function (job) {
        getApp().globalData.jobId = job.jobId;
        wx.redirectTo({ url: '/pages/ai-design/generating?stage=plane&count=' + count });
      });
    }).catch(function (err) {
      if (!err || !err.cancelled) wx.showToast({ title: (err && err.msg) || '创建任务失败', icon: 'none' });
      self.setData({ creating: false });
    });
  }
});
