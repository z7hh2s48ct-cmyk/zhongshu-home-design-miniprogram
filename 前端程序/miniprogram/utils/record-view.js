'use strict';
const format = require('./format');
function id(value) { return typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value) ? value : null; }
const titles = { projects: '我的方案', submissions: '我的投稿', favorites: '收藏户型', orders: '充值记录' };
function status(value) {
  return ({ ACTIVE: '设计档案', ARCHIVED: '已归档', DRAFT: '尚未完成', IN_PROGRESS: '设计中', COMPLETED: '方案已完成',
    VALIDATED: '校核通过，待提交', RESUBMITTED: '已重新提交，待审核', SUBMITTED: '待审核', PENDING: '待审核', PENDING_REVIEW: '待审核', IN_REVIEW: '审核中',
    APPROVED: '审核通过', REJECTED: '未通过', CHANGES_REQUESTED: '需修改', WITHDRAWN: '已撤回' })[value] || '状态待确认';
}
function payment(order) {
  const refund = order.refund;
  if (refund && refund.channelState === 'SUCCEEDED') return refund.pointReversalState === 'REVERSED' ? '已退款 · 设计点已冲正' : '已退款 · 设计点冲正中';
  if (refund && ['CREATED', 'PENDING', 'UNKNOWN'].includes(refund.channelState)) return '退款确认中';
  if (refund && refund.channelState === 'FAILED') return '退款失败 · 请联系客服';
  if (order.paymentState === 'SUCCEEDED') return order.fulfillmentState === 'CREDITED' ? '已支付 · 已到账' : '已支付 · 到账确认中';
  return ({ CREATED: '待支付', PENDING: '待支付', FAILED: '支付失败', CLOSED: '订单已关闭', CANCELLED: '已取消', UNKNOWN: '支付结果确认中', REFUNDED: '已退款' })[order.paymentState] || '状态待确认';
}
function amount(cents) { return Number.isSafeInteger(cents) && cents >= 0 ? (cents / 100).toFixed(2) : '—'; }
/** 项目列表状态：进行中任务优先，其次按阶段/结果给出下一步提示（UX 整改：列表不再只有编号时间） */
function projectState(raw) {
  const job = ({ QUEUED: '排队中', RUNNING: '生成中', VALIDATING: '结果校验中', CANCEL_REQUESTED: '取消处理中' })[raw.jobStatus];
  if (job) return job;
  if (raw.hasResult) return '方案已完成';
  return raw.stage === 'ELEVATION' ? '待选择立面方案' : '待选择平面方案';
}
function row(type, raw) {
  const key = { projects: 'projectId', submissions: 'submissionId', favorites: 'caseId', orders: 'orderId' }[type];
  const keyId = raw && id(raw[key]);
  if (!keyId) throw Error('记录编号无效，请重试');
  let title, hint, state, time, url;
  if (type === 'projects') {
    title = '设计方案 · ' + keyId.slice(-6); hint = raw.sourceType === 'CASE_REFERENCE' ? '基于户型库设计' : '自主设计';
    state = projectState(raw); time = raw.createdAt;
    url = '/pages/profile/record?type=projects&id=' + keyId;
    return { id: keyId, title, hint, status: state, time: format.shortTime(time), url,
      stage: raw.stage || 'FLAT', jobStatus: raw.jobStatus || '', hasResult: !!raw.hasResult,
      coverAssetId: raw.coverAssetId || '', coverUrl: '' };
  } else if (type === 'submissions') {
    title = '投稿 · ' + keyId.slice(-6); hint = '第 ' + (raw.currentRound || 1) + ' 轮审核';
    state = raw.publicationStatus === 'PUBLISHED' ? '已发布到户型库' : status(raw.status); time = raw.submittedAt;
    url = '/pages/profile/record?type=submissions&id=' + keyId;
  } else if (type === 'favorites') {
    title = raw.title || '收藏户型'; hint = [format.styleLabel(raw.styleCode), raw.buildingArea ? raw.buildingArea + '㎡' : '', raw.floorCount ? raw.floorCount + '层' : ''].filter(Boolean).join(' · ');
    state = '查看户型'; url = '/pages/library/detail?id=' + keyId + '&fromFavorites=1';
  } else {
    title = '充值 ¥' + amount(raw.amountCents); hint = '基础 ' + (raw.basePoints ?? '—') + ' 点 · 赠送 ' + (raw.bonusPoints ?? '—') + ' 点';
    state = payment(raw); time = raw.createdAt; url = '/pages/payment/success?orderId=' + keyId;
  }
  return { id: keyId, title, hint, status: state, time: format.shortTime(time), url };
}
function errorText(error) { return (error && (error.msg || error.message)) || '读取失败，请重试'; }
module.exports = { id, titles, status, payment, amount, row, errorText };
