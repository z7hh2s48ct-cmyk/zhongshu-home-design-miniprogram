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
export const getCase = (caseId) => request.get({ url: `${BASE}/cases/${caseId}` })
export const getCaseAsset = async (caseId, assetId): Promise<Blob> => {
  const base = `${BASE}/cases/${caseId}/assets/${assetId}`
  const result = await request.post({ url: `${base}/preview-tickets` })
  return request.download({ url: `${base}/content`, params: { ticket: result.ticket } })
}
export const createCase = (data) => request.post({ url: `${BASE}/cases`, data })
export const updateCase = (caseId, data) => request.patch({ url: `${BASE}/cases/${caseId}`, data })
export const uploadCaseImage = (caseId: string, data: FormData) =>
  request.post({
    url: `${BASE}/cases/${encodeURIComponent(caseId)}/images`,
    data,
    headersType: 'multipart/form-data',
    timeout: 120000
  })
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
export const publishSubmission = (submissionId, reason?: string) =>
  request.post({
    url: `${BASE}/submissions/${submissionId}/publication-commands`,
    data: reason ? { reason } : undefined
  })
export const getSubmissionAsset = async (submissionId, assetId): Promise<Blob> => {
  const base = `${BASE}/submissions/${submissionId}/assets/${assetId}`
  const result = await request.post({ url: `${base}/preview-tickets` })
  return request.download({ url: `${base}/content`, params: { ticket: result.ticket } })
}
export const bulkReview = (data) =>
  request.post({ url: `${BASE}/submissions/bulk-review-commands`, data })

// ---------- 授权码 ----------
export const getAccessCodePage = (params) => request.get({ url: `${BASE}/access-codes`, params })
export const getAccessCodeStats = () => request.get({ url: `${BASE}/access-codes/stats` })
export const disableAccessCode = (codeId) =>
  request.patch({ url: `${BASE}/access-codes/${codeId}`, data: { action: 'disable' } })
export const copyAccessCode = (codeId) =>
  request.post({ url: `${BASE}/access-codes/${codeId}/copy` })
export const deleteAccessCode = (codeId) =>
  request.delete({ url: `${BASE}/access-codes/${codeId}` })
export const createAccessCodeBatch = (data) =>
  request.post({ url: `${BASE}/access-code-batches`, data })
export const createDeliveryTicket = (batchId) =>
  request.post({ url: `${BASE}/access-code-batches/${batchId}/delivery-tickets` })
export const exportByTicket = (batchId, ticket) =>
  request.post({ url: `${BASE}/access-code-batches/${batchId}/delivery-exports`, data: { ticket } })
export const revokeAccessGrant = (grantId) =>
  request.post({ url: `${BASE}/access-grants/${grantId}/revocations` })

// F-1 授权码批次视图（分页，含每批次已兑换数）
export const getBatchPage = (params) => request.get({ url: `${BASE}/access-code-batches`, params })

// ---------- 充值方案 ----------
export const getPlanPage = (params) => request.get({ url: `${BASE}/recharge-plans`, params })
export const getPlan = (planId) => request.get({ url: `${BASE}/recharge-plans/${planId}` })
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
// T13-30 前半 ③：渠道支付流水分页（复用 payment_transaction，供财务对账）
export const getPaymentTransactionPage = (params) =>
  request.get({ url: `${BASE}/payment-transactions`, params })

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
// 停用账号：状态置 DISABLED 并立即撤销全部有效授权（identity:account:disable）
export const disableAccount = (accountId) =>
  request.patch({ url: `${BASE}/accounts/${accountId}`, data: { action: 'disable' } })

// ---------- AI 任务 ----------
export const getAiJobPage = (params) => request.get({ url: `${BASE}/ai-jobs`, params })
export const getAiJob = (jobId) => request.get({ url: `${BASE}/ai-jobs/${jobId}` })

// ---------- 导出 / 审计 ----------
export const createExportJob = (data) => request.post({ url: `${BASE}/export-jobs`, data })
export const getPrivacyRequestPage = (params) =>
  request.get({ url: `${BASE}/privacy-requests`, params })
export const decidePrivacyRequest = (id: string, data: { approve: boolean; reason: string }) =>
  request.post({ url: `${BASE}/privacy-requests/${encodeURIComponent(id)}/decision`, data })
export const getExportJobPage = (params) => request.get({ url: `${BASE}/export-jobs`, params })
export const getExportJob = (exportJobId) =>
  request.get({ url: `${BASE}/export-jobs/${exportJobId}` })
export const createExportDownloadTicket = (exportJobId) =>
  request.post({ url: `${BASE}/export-jobs/${exportJobId}/download-tickets` })
export const downloadExportFile = (exportJobId, ticket) =>
  request.download({ url: `${BASE}/export-jobs/${exportJobId}/content`, params: { ticket } })
// F-5 取消本人排队中/生成中的导出任务
export const cancelExportJob = (exportJobId) =>
  request.post({ url: `${BASE}/export-jobs/${exportJobId}/cancellation` })
export const getAuditEvents = (params) => request.get({ url: `${BASE}/audit-events`, params })

// ---------- F-2 运营公告 ----------
export const createAnnouncement = (data: { title: string; content: string }) =>
  request.post({ url: `${BASE}/announcements`, data })
