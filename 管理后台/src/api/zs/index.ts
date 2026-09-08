import request from '@/config/axios'

/**
 * 众墅之家设计管理后台 API（/admin-api/design/v1）
 * 对应后端 yudao-module-{identity,design,commerce,ai-orchestration} 的 admin 端点
 */
const BASE = '/design/v1'

// ---------- 工作台 ----------
export const getDashboardSummary = () => request.get({ url: `${BASE}/dashboard/summary` })

// ---------- 公司案例 ----------
export const getCasePage = (params) => request.get({ url: `${BASE}/cases`, params })
export const createCase = (data) => request.post({ url: `${BASE}/cases`, data })
export const updateCase = (caseId, data) => request.patch({ url: `${BASE}/cases/${caseId}`, data })
export const publishCase = (caseId) => request.post({ url: `${BASE}/cases/${caseId}/publications` })
export const offlineCase = (caseId, data) =>
  request.post({ url: `${BASE}/cases/${caseId}/withdrawals`, data })
export const bulkCaseAction = (data) => request.post({ url: `${BASE}/cases/bulk-actions`, data })

// ---------- 投稿审核 ----------
export const getSubmissionPage = (params) => request.get({ url: `${BASE}/submissions`, params })
export const getSubmission = (submissionId) =>
  request.get({ url: `${BASE}/submissions/${submissionId}` })
export const reviewDecision = (submissionId, data) =>
  request.post({ url: `${BASE}/submissions/${submissionId}/review-decisions`, data })
export const bulkReview = (data) =>
  request.post({ url: `${BASE}/submissions/bulk-review-commands`, data })

// ---------- 授权码 ----------
export const getAccessCodePage = (params) => request.get({ url: `${BASE}/access-codes`, params })
export const getAccessCodeStats = () => request.get({ url: `${BASE}/access-codes/stats` })
export const disableAccessCode = (codeId) =>
  request.patch({ url: `${BASE}/access-codes/${codeId}`, data: { action: 'disable' } })
export const createAccessCodeBatch = (data) =>
  request.post({ url: `${BASE}/access-code-batches`, data })
export const createDeliveryTicket = (batchId) =>
  request.post({ url: `${BASE}/access-code-batches/${batchId}/delivery-tickets` })
export const exportByTicket = (batchId, ticket) =>
  request.post({ url: `${BASE}/access-code-batches/${batchId}/delivery-exports`, data: { ticket } })
export const revokeAccessGrant = (grantId) =>
  request.post({ url: `${BASE}/access-grants/${grantId}/revocations` })

// ---------- 充值方案 ----------
export const getPlanPage = (params) => request.get({ url: `${BASE}/recharge-plans`, params })
export const createPlan = (data) => request.post({ url: `${BASE}/recharge-plans`, data })
export const updatePlan = (planId, data) =>
  request.patch({ url: `${BASE}/recharge-plans/${planId}`, data })

// ---------- 充值订单 / 退款 ----------
export const getOrderPage = (params) => request.get({ url: `${BASE}/recharge-orders`, params })
export const getOrder = (orderId) => request.get({ url: `${BASE}/recharge-orders/${orderId}` })
export const createRefundRequest = (orderId, data) =>
  request.post({ url: `${BASE}/recharge-orders/${orderId}/refund-requests`, data })
export const getRefundOrderPage = (params) => request.get({ url: `${BASE}/refund-orders`, params })
export const reconcileOrder = (orderId, data) =>
  request.post({ url: `${BASE}/recharge-orders/${orderId}/reconciliation`, data })

// ---------- 设计点流水 / 调点 ----------
export const getPointLedgerPage = (params) => request.get({ url: `${BASE}/point-ledger`, params })
export const getAdjustmentPage = (params) =>
  request.get({ url: `${BASE}/manual-point-adjustments`, params })
export const createManualAdjustment = (data) =>
  request.post({ url: `${BASE}/manual-point-adjustments`, data })
export const reviewManualAdjustment = (adjustmentId, data) =>
  request.post({ url: `${BASE}/manual-point-adjustments/${adjustmentId}/review-decisions`, data })

// ---------- C 端用户 ----------
export const getAccountPage = (params) => request.get({ url: `${BASE}/accounts`, params })
export const getAccount = (accountId) => request.get({ url: `${BASE}/accounts/${accountId}` })

// ---------- AI 任务 ----------
export const getAiJobPage = (params) => request.get({ url: `${BASE}/ai-jobs`, params })
export const getAiJob = (jobId) => request.get({ url: `${BASE}/ai-jobs/${jobId}` })

// ---------- 导出 / 审计 ----------
export const createExportJob = (data) => request.post({ url: `${BASE}/export-jobs`, data })
export const getExportJob = (exportJobId) =>
  request.get({ url: `${BASE}/export-jobs/${exportJobId}` })
export const createExportDownloadTicket = (exportJobId) =>
  request.post({ url: `${BASE}/export-jobs/${exportJobId}/download-tickets` })
export const downloadExportFile = (exportJobId, ticket) =>
  request.download({ url: `${BASE}/export-jobs/${exportJobId}/content`, params: { ticket } })
export const getAuditEvents = (params) => request.get({ url: `${BASE}/audit-events`, params })
