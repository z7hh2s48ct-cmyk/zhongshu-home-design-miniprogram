'use strict';
const api = require('../../utils/api');
const http = require('../../utils/request');
const config = require('../../utils/config');
const view = require('../../utils/record-view');
const stateText = { PENDING: '待处理', PROCESSING: '处理中', COMPLETED: '已完成', REJECTED: '未完成' };
Page({
  data: { consents: [], requests: [], busy: false, loading: false, error: '' },
  onShow() { this._token = http.captureSession ? http.captureSession() : http.getToken(); this.setData({ busy: false }); return this.load(); },
  onHide() { this._seq = (this._seq || 0) + 1; },
  onUnload() { this.onHide(); },
  current(seq) {
    if (seq !== this._seq) return false;
    if (http.isSameSession(this._token)) return true;
    this.setData({ consents: [], requests: [], busy: false, loading: false, error: '登录状态已变化，请重新进入' }); return false;
  },
  load() {
    const seq = this._seq = (this._seq || 0) + 1;
    if (!this._token) { this.setData({ consents: [], requests: [], error: '请先通过微信登录后查看本人隐私申请' }); return; }
    this.setData({ loading: true, error: '' });
    return Promise.all([api.getPrivacyConsents(), api.listPrivacyRequests()]).then(([consents, requests]) => {
      if (!this.current(seq)) return;
      const policies = { USER_AGREEMENT: '用户协议', PRIVACY_POLICY: '隐私政策', AI_PROCESSING_NOTICE: '第三方 AI 处理告知' };
      this.setData({ loading: false, consents: (consents || []).map(row => ({ ...row, name: policies[row.policyType] || '协议' })),
        requests: (requests || []).map(row => ({ ...row, label: row.requestType === 'EXPORT' ? '资料与记录导出' : '账号关闭', statusText: stateText[row.status] || '待确认',
          errorText: ['EXPORT_ROW_LIMIT', 'EXPORT_SIZE_LIMIT'].includes(row.errorCode) ? '数据量较大，请联系客服申请分批导出' : row.errorCode ? '处理未完成，请重试或联系客服' : '' })) });
    }).catch(error => { if (this.current(seq)) this.setData({ loading: false, error: view.errorText(error) }); });
  },
  createExport() { return this.submit(() => api.createDataExportRequest()); },
  requestClosure() {
    if (this.data.busy) return;
    wx.showModal({ title: '申请关闭账号', content: '请先导出需要保留的资料。申请须核对余额、订单、退款和生成任务；完成关闭后将退出全部设备并撤回作品公开展示，账务等资料按确认的留存规则处理。', confirmText: '提交申请', success: result => { if (result.confirm) this.submit(() => api.createAccountClosureRequest()); } });
  },
  submit(action) {
    if (this.data.busy || !this.current(this._seq)) return;
    this.setData({ busy: true, error: '' }); const seq = this._seq;
    return action().then(() => { if (this.current(seq)) { this.setData({ busy: false }); return this.load(); } }).catch(error => { if (this.current(seq)) this.setData({ error: view.errorText(error) }); })
      .finally(() => { if (this.current(seq)) this.setData({ busy: false }); });
  },
  download(event) {
    const id = view.id(event.currentTarget.dataset.id); if (!id || this.data.busy || !this.current(this._seq)) return;
    const seq = this._seq; this.setData({ busy: true, error: '' });
    return api.createPrivacyDownloadTicket(id).then(ticket => {
      if (!this.current(seq)) return;
      return new Promise((resolve, reject) => wx.downloadFile({ url: config.apiBase + '/app-api/design/v1/privacy-requests/' + id + '/content?ticket=' + encodeURIComponent(ticket.ticket),
        header: { Authorization: 'Bearer ' + http.getToken(), 'tenant-id': String(config.tenantId) },
        success: result => {
          if (!this.current(seq)) { resolve(); return; }
          if (result.statusCode !== 200) { reject(Error('下载失败，请重新领取下载票据')); return; }
          if (typeof wx.shareFileMessage === 'function') wx.shareFileMessage({ filePath: result.tempFilePath, fileName: '个人数据-' + id + '.json', complete: resolve });
          else { wx.showModal({ title: '下载已完成', content: '请升级微信后使用文件分享功能保存导出文件。', showCancel: false }); resolve(); }
        }, fail: () => reject(Error('文件下载失败，请重试')) }));
    }).catch(error => { if (this.current(seq)) this.setData({ error: view.errorText(error) }); })
      .finally(() => { if (this.current(seq)) this.setData({ busy: false }); });
  },
  login() { wx.navigateTo({ url: '/pages/auth/index' }); },
  contact() { return require('../../utils/support').contact(); }
});
