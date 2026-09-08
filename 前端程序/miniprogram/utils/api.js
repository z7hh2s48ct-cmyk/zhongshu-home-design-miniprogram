'use strict';

var http = require('./request');

var BASE = '/app-api/design/v1';

module.exports = {
  // ---- 身份 ----
  login: function (wxCode) {
    return http.post(BASE + '/auth/wechat-login', { code: wxCode });
  },
  getAccessGrant: function () {
    return http.get(BASE + '/access-grant');
  },
  redeemAccessCode: function (accessCode) {
    return http.post(BASE + '/access-code-redemptions', { accessCode: accessCode });
  },
  getProfile: function () {
    return http.get(BASE + '/profile');
  },

  // ---- 首页 / 户型库 ----
  getHome: function () {
    return http.get(BASE + '/home');
  },
  listCases: function (params) {
    var qs = Object.keys(params || {}).filter(function (k) { return params[k] != null; })
      .map(function (k) { return k + '=' + encodeURIComponent(params[k]); }).join('&');
    return http.get(BASE + '/cases' + (qs ? '?' + qs : ''));
  },
  getCaseDetail: function (caseId) {
    return http.get(BASE + '/cases/' + caseId);
  },
  favorite: function (caseId) {
    return http.put(BASE + '/cases/' + caseId + '/favorite');
  },
  unfavorite: function (caseId) {
    return http.del(BASE + '/cases/' + caseId + '/favorite');
  },
  listFavorites: function (cursor, limit) {
    return http.get(BASE + '/favorites?cursor=' + (cursor || '') + '&limit=' + (limit || 20));
  },

  // ---- 设计项目 / AI 任务 ----
  createProject: function (body) {
    return http.post(BASE + '/design-projects', body || null);
  },
  getProject: function (projectId, jobId) {
    return http.get(BASE + '/design-projects/' + projectId + (jobId ? '?jobId=' + jobId : ''));
  },
  createFlatJob: function (projectId, count, idemKey) {
    return http.post(BASE + '/design-projects/' + projectId + '/flat-jobs',
      { count: count }, { 'Idempotency-Key': idemKey });
  },
  selectFlat: function (projectId, jobId, candidateId) {
    return http.post(BASE + '/design-projects/' + projectId + '/flat-selections',
      { jobId: String(jobId), candidateId: String(candidateId) });
  },
  createElevationJob: function (projectId, config, idemKey) {
    return http.post(BASE + '/design-projects/' + projectId + '/elevation-jobs',
      config, { 'Idempotency-Key': idemKey });
  },
  selectElevation: function (projectId, jobId, candidateId) {
    return http.post(BASE + '/design-projects/' + projectId + '/elevation-selections',
      { jobId: String(jobId), candidateId: String(candidateId) });
  },
  createRevisionRequest: function (projectId, body, idemKey) {
    return http.post(BASE + '/design-projects/' + projectId + '/revision-requests',
      body || {}, { 'Idempotency-Key': idemKey });
  },
  getResultVersions: function (projectId) {
    return http.get(BASE + '/design-projects/' + projectId + '/result-versions');
  },
  getAiJob: function (jobId) {
    return http.get(BASE + '/ai-jobs/' + jobId);
  },
  cancelJob: function (jobId) {
    return http.post(BASE + '/ai-jobs/' + jobId + '/cancellation-requests');
  },

  // ---- 充值 ----
  listRechargePlans: function () {
    return http.get(BASE + '/recharge-plans');
  },
  createRechargeOrder: function (planId, idemKey) {
    return http.post(BASE + '/recharge-orders?planId=' + planId, null,
      { 'Idempotency-Key': idemKey });
  },
  getRechargeOrder: function (orderId) {
    return http.get(BASE + '/recharge-orders/' + orderId);
  },
  getPointAccount: function () {
    return http.get(BASE + '/point-account');
  },
  getPointLedger: function (pageNo, pageSize) {
    return http.get(BASE + '/point-ledger?pageNo=' + (pageNo || 1) + '&pageSize=' + (pageSize || 20));
  },

  // ---- 消息 ----
  listMessages: function (cursor, limit) {
    return http.get(BASE + '/messages?cursor=' + (cursor || '') + '&limit=' + (limit || 20));
  },
  getUnreadCount: function () {
    return http.get(BASE + '/messages/unread-count');
  },
  markMessageRead: function (messageId) {
    return http.post(BASE + '/messages/' + messageId + '/read-receipts');
  },

  // ---- 资产 ----
  getUploadTicket: function (assetType, mimeType, sizeBytes, sha256) {
    return http.post(BASE + '/assets/upload-tickets',
      { assetType: assetType, mimeType: mimeType, sizeBytes: String(sizeBytes), sha256: sha256 });
  },
  completeUpload: function (assetId) {
    return http.post(BASE + '/assets/' + assetId + '/complete');
  },
  createDownloadTicket: function (assetId) {
    return http.post(BASE + '/assets/' + assetId + '/download-tickets');
  },

  // ---- 预算 ----
  createBudgetEstimate: function (projectId, input) {
    return http.post(BASE + '/design-projects/' + projectId + '/budget-estimates', input);
  },

  // ---- 投稿 ----
  validatePublication: function (projectId, data) {
    return http.post(BASE + '/design-projects/' + projectId + '/publication-validations', data || null);
  },
  submitForPublication: function (projectId, data, idemKey) {
    return http.post(BASE + '/design-projects/' + projectId + '/submissions', data,
      { 'Idempotency-Key': idemKey });
  },

  // ---- 协议 ----
  getPrivacyConsents: function () {
    return http.get(BASE + '/privacy-consents');
  },
  acceptPrivacyConsents: function () {
    return http.post(BASE + '/privacy-consents');
  }
};
