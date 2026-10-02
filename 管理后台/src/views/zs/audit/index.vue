<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">操作记录</h1>
        <div class="zs-page-subtitle"
          >谁在什么时间对什么做了什么：调点复核、激活码交付、发布上架、退款等全部留痕，可按时间/类型/操作者/对象取证</div
        >
      </div>
      <el-button
        v-hasPermi="['design:export:manage']"
        class="zs-btn-primary"
        @click="$router.push('/zs/export')"
        ><Icon icon="ep:download" class="mr-4px" /> 导出操作记录</el-button
      >
    </div>

    <div class="zs-table-card">
      <el-form inline class="zs-filter">
        <el-form-item label="时间范围">
          <el-date-picker
            v-model="query.range"
            type="datetimerange"
            start-placeholder="开始（含）"
            end-placeholder="结束（不含）"
            style="width: 340px"
          />
        </el-form-item>
        <el-form-item label="事件类型">
          <el-select
            v-model="query.eventType"
            clearable
            placeholder="全部"
            style="width: 160px"
            @change="search"
          >
            <el-option
              v-for="(label, value) in EVENT_TYPES"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="操作者">
          <el-input
            v-model="query.actorId"
            placeholder="管理员/用户编号"
            clearable
            style="width: 170px"
            @keyup.enter="search"
          />
        </el-form-item>
        <el-form-item label="业务对象">
          <el-select
            v-model="query.bizType"
            clearable
            placeholder="类型"
            style="width: 130px"
            @change="search"
          >
            <el-option
              v-for="(label, value) in BIZ_TYPES"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
          <el-input
            v-model="query.bizId"
            placeholder="对象编号"
            clearable
            style="width: 170px; margin-left: 8px"
            @keyup.enter="search"
          />
        </el-form-item>
        <el-form-item>
          <el-button class="zs-btn-primary" @click="search">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="loadError"
        type="error"
        :closable="false"
        title="操作记录加载失败，请重试（当前列表不代表完整留痕）"
        style="margin-bottom: 12px"
        ><el-button @click="load">重新加载</el-button></el-alert
      >
      <el-alert
        v-if="!loading && !loadError && !list.length"
        type="info"
        :closable="false"
        title="当前筛选条件下没有操作记录；清空筛选可查看全部"
        style="margin-bottom: 12px"
      />

      <div v-if="selectedRows.length" style="margin-bottom: 10px">
        <el-button size="small" type="danger" plain @click="deleteSelected"
          >批量删除（{{ selectedRows.length }}）</el-button
        >
      </div>
      <el-table
        :data="list"
        v-loading="loading"
        stripe
        @row-click="toggleDetail"
        @selection-change="(rows: any[]) => (selectedRows = rows)"
      >
        <el-table-column type="selection" width="42" />
        <el-table-column type="expand" title="详情">
          <template #default="{ row }">
            <div class="zs-audit-detail-block">
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item
                  v-for="item in detailRows(row.detail)"
                  :key="item.k"
                  :label="item.label"
                >
                  <span :class="{ 'zs-audit-red': item.bad }">{{ item.text }}</span>
                </el-descriptions-item>
              </el-descriptions>
              <el-collapse class="mt-8px">
                <el-collapse-item title="原始数据（JSON）" name="raw">
                  <pre class="zs-audit-detail">{{ prettyDetail(row.detail) }}</pre>
                </el-collapse-item>
              </el-collapse>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="事件号" prop="id" width="200" />
        <el-table-column label="事件类型" width="150">
          <template #default="{ row }">{{ auditEventText(row.event_type) }}</template>
        </el-table-column>

        <el-table-column label="操作者" width="160">
          <template #default="{ row }"
            >{{ actorTypeText(row.actor_type) }} {{ row.actor_id }}</template
          >
        </el-table-column>
        <el-table-column label="动作" width="110">
          <template #default="{ row }">{{ actionText[row.action] || row.action }}</template>
        </el-table-column>
        <el-table-column label="业务对象" min-width="180">
          <template #default="{ row }">
            <span v-if="linkOf(row)" class="zs-link" @click.stop="$router.push(linkOf(row)!.path)"
              >{{ row.biz_type }}{{ row.biz_id ? ':' + row.biz_id : '' }} →</span
            >
            <span v-else>{{ row.biz_type }}{{ row.biz_id ? ':' + row.biz_id : '' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <span
              class="zs-tag"
              :class="row.result === 'SUCCESS' ? 'zs-tag--green' : 'zs-tag--red'"
              >{{ auditResultText(row.result) }}</span
            >
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <span class="zs-link-danger" @click.stop="deleteRow(row)">删除</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ fmtTime(row.create_time, true) }}</template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="mt-16px"
        layout="total, sizes, prev, pager, next"
        :total="total"
        v-model:page-size="query.pageSize"
        @size-change="search"
        v-model:current-page="query.pageNo"
        @current-change="load"
      />
    </div>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { fmtTime, auditEventText, auditResultText } from '@/utils/zsFormat'

const actionText: Record<string, string> = {
  EXECUTE: '执行',
  CREATE: '创建',
  REVIEW: '审核',
  APPROVE: '通过',
  REJECT: '拒绝',
  PUBLISH: '发布',
  DISABLE: '停用',
  REVOKE: '解绑',
  EXPORT: '导出',
  REDEEM: '兑换',
  SEND: '发送'
}

// E-1 取证筛选候选：事件类型与业务对象按现有留痕口径枚举
const EVENT_TYPES: Record<string, string> = {
  MANUAL_POINT_ADJUSTMENT: '人工调点',
  ACCESS_CODE_ISSUED: '授权码发放',
  ACCESS_CODE_DISABLED: '授权码停用',
  ACCESS_CODE_REDEEMED: '授权码兑换',
  ACCESS_GRANT_REVOKED: '授权解绑',
  SUBMISSION_REVIEWED: '投稿审核',
  SUBMISSION_PUBLISHED: '投稿发布',
  ORDER_REFUND: '订单退款',
  EXPORT_FILE: '数据导出',
  BUDGET_QUOTE_CHANGED: '报价变更',
  ANNOUNCEMENT: '运营公告'
}
const BIZ_TYPES: Record<string, string> = {
  ai_job: 'AI任务',
  recharge_order: '充值订单',
  manual_point_adjustment: '人工调点',
  case_submission: '投稿',
  refund_order: '退款单',
  budget_quote: '报价',
  user_message: '站内消息',
  export_job: '导出任务'
}

defineOptions({ name: 'ZsAudit' })
const message = useMessage()

const loading = ref(false)
/** 接口失败标记：用于把“加载失败”与“确实没有数据”区分开 */
const loadError = ref(false)
const list = ref<any[]>([])
const total = ref(0)

// 2026-10-02 运营决策：操作记录可删（audit_event 无 deleted 列 → 物理删除）
const selectedRows = ref<any[]>([])
async function deleteRow(row: any) {
  try {
    await message.confirm('删除该操作记录？该记录将被物理删除且不留痕，请谨慎操作。')
  } catch {
    return
  }
  try {
    const ok = await ZsApi.deleteAdminData('audit-event', row.id)
    if (!ok) {
      message.warning('记录不存在')
      return
    }
    message.success('已删除')
    await load()
  } catch (e: any) {
    message.error(e?.msg || '删除失败，请重试')
  }
}
async function deleteSelected() {
  if (!selectedRows.value.length) return
  try {
    await message.confirm(
      '批量删除选中的 ' + selectedRows.value.length + ' 条操作记录？（物理删除，不留痕）'
    )
  } catch {
    return
  }
  try {
    const res = await ZsApi.batchDeleteAdminData(
      'audit-event',
      selectedRows.value.map((r) => r.id)
    )
    message.success('已删除 ' + (res?.deleted ?? 0) + ' / ' + selectedRows.value.length)
    await load()
  } catch (e: any) {
    message.error(e?.msg || '批量删除失败，请重试')
  }
}

const query = reactive({
  eventType: '',
  actorId: '',
  bizType: '',
  bizId: '',
  range: [] as Date[],
  pageNo: 1,
  pageSize: 20
})

const buildParams = () => ({
  eventType: query.eventType || undefined,
  actorId: query.actorId?.trim() || undefined,
  bizType: query.bizType || undefined,
  bizId: query.bizId?.trim() || undefined,
  from: query.range?.[0]?.toISOString(),
  to: query.range?.[1]?.toISOString(),
  pageNo: query.pageNo,
  pageSize: query.pageSize
})

// 业务对象深链：留痕到业务现场一步直达
const linkOf = (row: any): { path: string } | null => {
  if (!row.biz_type) return null
  const targets: Record<string, string> = {
    ai_job: '/zs/ai-job',
    recharge_order: '/zs/recharge?tab=orders',
    refund_order: '/zs/recharge?tab=orders',
    manual_point_adjustment: '/zs/point-adjustments',
    case_submission: '/zs/review',
    budget_quote: '/zs/budget-estimates',
    export_job: '/zs/export',
    user_message: '/zs/announcement'
  }
  return targets[row.biz_type] ? { path: targets[row.biz_type] } : null
}

const actorTypeText = (t: string) =>
  ({ ADMIN: '管理员', USER: '用户', SYSTEM: '系统', WORKER: '任务器' })[t] || t

// 审计 detail 字段的中文映射：键名 + 常见枚举值（审计要“人物执行事件的具体详情”且用中文可读）
const DETAIL_LABELS: Record<string, string> = {
  budgetId: '预算编号',
  reason: '原因',
  recipients: '送达人数',
  title: '标题',
  submissionId: '投稿编号',
  caseId: '案例编号',
  userId: '用户编号',
  projectId: '项目编号',
  unitPointCost: '单价（设计点）',
  minCount: '最少张数',
  maxCount: '最多张数',
  stage: '阶段',
  resolution: '清晰度',
  effectiveAt: '生效时间',
  expiresAt: '失效时间',
  quoteId: '报价编号',
  revisionId: '修订编号',
  adjustmentCents: '调价（分）',
  finalPriceCents: '最终价（分）',
  quantity: '数量',
  codeMask: '授权码掩码',
  batchId: '批次编号',
  orderId: '订单编号',
  refundId: '退款单编号',
  points: '点数',
  targetUserId: '目标用户',
  delta: '调整点数',
  comment: '意见',
  confirmedBuildingArea: '核定面积'
}
const VALUE_TEXT: Record<string, Record<string, string>> = {
  stage: { FLAT: '平面', ELEVATION: '立面' },
  resolution: { '2K': '2K', '4K': '4K' },
  result: { SUCCESS: '成功', FAILURE: '失败', DENIED: '拒绝' }
}
const fmtValue = (key: string, value: unknown): string => {
  if (value === null || value === undefined || value === '') return '—'
  // 时间类字段转北京时间可读
  if (/(At|Time)$/.test(key) && typeof value === 'string') {
    const d = new Date(value)
    if (!isNaN(d.getTime()))
      return d.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
  }
  const mapped = VALUE_TEXT[key]?.[String(value)]
  if (mapped) return mapped
  if (typeof value === 'number') return String(value)
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value)
    } catch {
      return String(value)
    }
  }
  return String(value)
}
const detailRows = (raw: any): Array<{ k: string; label: string; text: string; bad?: boolean }> => {
  let obj: Record<string, unknown>
  try {
    obj = typeof raw === 'string' ? JSON.parse(raw) : (raw as Record<string, unknown>) || {}
  } catch {
    return [{ k: '_raw', label: '原始内容', text: String(raw ?? '（无）') }]
  }
  const entries = Object.entries(obj || {})
  if (!entries.length) return [{ k: '_empty', label: '详情', text: '（该事件无附加明细）' }]
  return entries.map(([k, v]) => ({
    k,
    label: DETAIL_LABELS[k] || k,
    text: fmtValue(k, v),
    bad: k === 'result' && v !== 'SUCCESS'
  }))
}

const prettyDetail = (raw: any) => {
  if (!raw) return '（无明细）'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return String(raw)
  }
}
const toggleDetail = () => {}

const search = () => {
  query.pageNo = 1
  return load()
}

const load = async () => {
  loading.value = true
  loadError.value = false
  try {
    const res = await ZsApi.getAuditEvents(buildParams())
    list.value = res?.list || []
    total.value = res?.total || 0
  } catch {
    // 审计事件是取证依据：加载失败必须显式提示，不能被当成"无审计记录"
    list.value = []
    total.value = 0
    loadError.value = true
    message.error('审计事件加载失败')
  } finally {
    loading.value = false
  }
}

const reset = () => {
  query.eventType = ''
  query.actorId = ''
  query.bizType = ''
  query.bizId = ''
  query.range = []
  query.pageNo = 1
  load()
}

onMounted(load)
</script>

<style lang="scss" scoped>
.zs-filter {
  margin-bottom: 6px;
}

.zs-audit-detail-block {
  padding: 0 8px;
}

.zs-audit-red {
  font-weight: 600;
  color: #d0342c;
}

.zs-audit-detail {
  padding: 12px 16px;
  margin: 0 16px;
  font-size: 12px;
  line-height: 1.8;
  word-break: break-all;
  white-space: pre-wrap;
  background: #faf6f0;
  border-radius: 6px;
}
</style>
