<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">审计事件</h1>
        <div class="zs-page-subtitle"
          >全部管理操作留痕：制单复核、授权码交付、发布上架、退款与调点；支持按时间/类型/操作者/业务对象取证</div
        >
      </div>
      <el-button
        v-hasPermi="['design:export:manage']"
        class="zs-btn-primary"
        @click="$router.push('/zs/export')"
        ><Icon icon="ep:download" class="mr-4px" /> 导出审计事件</el-button
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
        title="审计事件加载失败，请重试（当前列表不代表完整审计记录）"
        style="margin-bottom: 12px"
        ><el-button @click="load">重新加载</el-button></el-alert
      >
      <el-alert
        v-if="!loading && !loadError && !list.length"
        type="info"
        :closable="false"
        title="当前筛选条件下没有审计事件；清空筛选可查看全部留痕"
        style="margin-bottom: 12px"
      />

      <el-table :data="list" v-loading="loading" stripe @row-click="toggleDetail">
        <el-table-column type="expand">
          <template #default="{ row }">
            <pre class="zs-audit-detail">{{ prettyDetail(row.detail) }}</pre>
          </template>
        </el-table-column>
        <el-table-column label="事件号" prop="id" width="200" />
        <el-table-column label="事件类型" width="150">
          <template #default="{ row }">{{ auditEventText(row.event_type) }}</template>
        </el-table-column>

        <el-table-column label="操作者" width="160">
          <template #default="{ row }">{{ row.actor_type }}:{{ row.actor_id }}</template>
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
