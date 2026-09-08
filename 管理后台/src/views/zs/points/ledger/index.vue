<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">设计点流水</h1>
        <div class="zs-page-subtitle">只追加账本：充值、赠送、生成扣点、失败退回、人工调整全程留痕</div>
      </div>
    </div>

    <div class="zs-table-card">
      <el-tabs v-model="activeTab" @tab-change="load">
        <el-tab-pane label="点数流水" name="ledger" />
        <el-tab-pane label="人工调点单" name="adjustment" />
      </el-tabs>

      <div v-if="activeTab === 'ledger'">
      <el-form inline class="zs-filter">
        <el-form-item label="用户">
          <el-input v-model="query.userId" placeholder="用户编号" clearable style="width: 160px" @keyup.enter="load" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="query.type" clearable placeholder="全部" style="width: 180px" @change="load">
            <el-option label="充值基础点" value="RECHARGE_BASE_CREDIT" />
            <el-option label="充值赠送点" value="RECHARGE_BONUS_CREDIT" />
            <el-option label="平面生成扣点" value="FLAT_GENERATION_DEBIT" />
            <el-option label="立面生成扣点" value="ELEVATION_GENERATION_DEBIT" />
            <el-option label="结算退回" value="TASK_SETTLEMENT_REFUND" />
            <el-option label="人工调增" value="MANUAL_CREDIT" />
            <el-option label="人工调减" value="MANUAL_DEBIT" />
            <el-option label="退款冲正(基础)" value="RECHARGE_BASE_REVERSAL" />
            <el-option label="退款冲正(赠送)" value="RECHARGE_BONUS_REVERSAL" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button class="zs-btn-primary" @click="load">查询</el-button>
          <el-button @click="reset">重置</el-button>
          <el-button type="warning" plain @click="adjustDialog.visible = true">人工调点</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column label="流水号" prop="ledgerId" width="180" />
        <el-table-column label="用户" prop="userId" width="110" />
        <el-table-column label="类型" width="140">
          <template #default="{ row }">
            <span class="zs-tag" :class="(row.delta || 0) > 0 ? 'zs-tag--green' : 'zs-tag--red'">{{ typeText(row.type) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="变化" width="90">
          <template #default="{ row }">
            <span :style="{ color: (row.delta || 0) > 0 ? '#3f9e56' : '#d0342c', fontWeight: 600 }">
              {{ (row.delta || 0) > 0 ? '+' : '' }}{{ row.delta }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="变动后余额" prop="balanceAfter" width="110" />
        <el-table-column label="业务类型" width="120">
          <template #default="{ row }">{{ bizText(row.bizType) }}</template>
        </el-table-column>
        <el-table-column label="业务编号" prop="bizId" width="150" />
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
      </div>

      <div v-else>
      <el-form inline class="zs-filter">
        <el-form-item label="状态">
          <el-select v-model="adjustQuery.status" clearable placeholder="全部" style="width: 180px" @change="load">
            <el-option label="待复核" value="SUBMITTED" />
            <el-option label="已执行" value="EXECUTED" />
            <el-option label="已驳回" value="REJECTED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button class="zs-btn-primary" @click="load">查询</el-button>
          <el-button type="warning" plain @click="adjustDialog.visible = true">人工调点</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column label="调点单号" prop="id" width="180" />
        <el-table-column label="目标用户" prop="target_user_id" width="120" />
        <el-table-column label="调整" width="90">
          <template #default="{ row }">
            <span :style="{ color: (row.delta || 0) > 0 ? '#3f9e56' : '#d0342c', fontWeight: 600 }">
              {{ (row.delta || 0) > 0 ? '+' : '' }}{{ row.delta }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="原因" prop="reason" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="zs-tag" :class="adjustStatusClass(row.status)">{{ adjustStatusText(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="制单人" prop="maker_user_id" width="110" />
        <el-table-column label="复核人" prop="checker_user_id" width="110" />
        <el-table-column label="复核意见" prop="checker_comment" min-width="140" show-overflow-tooltip />
        <el-table-column label="制单时间" width="160">
            <template #default="{ row }">{{ fmtTime(row.create_time) }}</template>
          </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'SUBMITTED'">
              <el-button size="small" type="success" plain @click="openReview(row, true)">通过</el-button>
              <el-button size="small" type="danger" plain @click="openReview(row, false)">驳回</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      </div>

      <el-pagination
        class="mt-16px"
        layout="total, sizes, prev, pager, next"
        :total="total"
        :page-size="query.pageSize"
        v-model:current-page="query.pageNo"
        @current-change="load"
      />

      <div class="zs-footnote">
        安全规则：人工调点须制单/复核双人分离（复核人与制单人不可为同一人）；已执行流水不可修改，只能反向调整。
      </div>
    </div>

    <!-- 人工调点制单 -->
    <el-dialog v-model="adjustDialog.visible" title="人工调点（制单）" width="460px">
      <el-form label-width="90px">
        <el-form-item label="目标用户" required>
          <el-input v-model="adjustDialog.targetUserId" placeholder="C 端用户编号" style="width: 240px" />
        </el-form-item>
        <el-form-item label="调整点数" required>
          <el-input-number v-model="adjustDialog.delta" :step="10" />
          <span class="ml-8px" style="color: #8a8a8a; font-size: 12px">正数为调增，负数为调减</span>
        </el-form-item>
        <el-form-item label="原因" required>
          <el-input v-model="adjustDialog.reason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="如：生成失败补偿 / 活动赠送" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustDialog.visible = false">取消</el-button>
        <el-button class="zs-btn-primary" :loading="adjustDialog.submitting" @click="submitAdjustment">提交制单</el-button>
      </template>
    </el-dialog>

    <!-- 人工调点复核 -->
    <el-dialog v-model="reviewDialog.visible" :title="reviewDialog.approve ? '调点复核 · 通过' : '调点复核 · 驳回'" width="420px">
      <div v-if="reviewDialog.row" style="margin-bottom: 12px; font-size: 13px; color: #606266">
        单号 {{ reviewDialog.row.id }} · 用户 {{ reviewDialog.row.target_user_id }} ·
        调整 {{ reviewDialog.row.delta }} 点 · 制单人 {{ reviewDialog.row.maker_user_id }}
      </div>
      <el-input v-model="reviewDialog.comment" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="复核意见（必填）" />
      <template #footer>
        <el-button @click="reviewDialog.visible = false">取消</el-button>
        <el-button :type="reviewDialog.approve ? 'success' : 'danger'" :loading="reviewDialog.submitting" @click="submitReview">
          {{ reviewDialog.approve ? '确认通过并执行' : '确认驳回' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { fmtTime } from '@/utils/zsFormat'
import { ElMessage } from 'element-plus'

defineOptions({ name: 'ZsPointLedger' })

const activeTab = ref('ledger')
const loading = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const query = reactive({ userId: '', type: '', pageNo: 1, pageSize: 10 })
const adjustQuery = reactive({ status: 'SUBMITTED' })

const bizText = (b: string) =>
  ({
    ai_job: 'AI任务', recharge_order: '充值订单', manual_point_adjustment: '人工调点',
    case_submission: '投稿', refund_order: '退款单', design_project: '设计项目',
    MANUAL_ADJUSTMENT: '人工调点'
  }[b] || b)
const typeText = (t: string) =>
  ({
    RECHARGE_BASE_CREDIT: '充值基础点',
    RECHARGE_BONUS_CREDIT: '充值赠送点',
    FLAT_GENERATION_DEBIT: '平面生成扣点',
    ELEVATION_GENERATION_DEBIT: '立面生成扣点',
    TASK_SETTLEMENT_REFUND: '结算退回',
    MANUAL_CREDIT: '人工调增',
    MANUAL_DEBIT: '人工调减',
    RECHARGE_BASE_REVERSAL: '退款冲正(基础)',
    RECHARGE_BONUS_REVERSAL: '退款冲正(赠送)'
  }[t] || t)

const load = async () => {
  loading.value = true
  try {
    if (activeTab.value === 'adjustment') {
      const res = await ZsApi.getAdjustmentPage({ ...adjustQuery, pageNo: query.pageNo, pageSize: query.pageSize })
      list.value = res?.list || []
      total.value = res?.total || 0
      return
    }
    const res = await ZsApi.getPointLedgerPage({
      ...query,
      userId: query.userId || undefined,
      type: query.type || undefined
    })
    list.value = res?.list || []
    total.value = res?.total || 0
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}
const reset = () => {
  query.userId = ''
  query.type = ''
  query.pageNo = 1
  load()
}

// ---- 人工调点：制单 + 复核 ----
const adjustDialog = reactive({ visible: false, submitting: false, targetUserId: '', delta: 10, reason: '' })
const reviewDialog = reactive({ visible: false, submitting: false, approve: true, comment: '', row: null as any })

const submitAdjustment = async () => {
  const targetUserId = String(adjustDialog.targetUserId || '').trim()
  if (!/^\d{1,20}$/.test(targetUserId) || !adjustDialog.delta || !adjustDialog.reason.trim()) {
    ElMessage.warning('请填写正确的目标用户编号、调整点数与原因')
    return
  }
  adjustDialog.submitting = true
  try {
    // 目标用户以字符串提交：19 位雪花编号超出 JS 安全整数，后端按字符串解析
    await ZsApi.createManualAdjustment({
      targetUserId: targetUserId,
      delta: adjustDialog.delta,
      reason: adjustDialog.reason.trim()
    })
    ElMessage.success('制单成功，等待另一管理员复核')
    adjustDialog.visible = false
    adjustDialog.targetUserId = ''
    adjustDialog.delta = 10
    adjustDialog.reason = ''
    activeTab.value = 'adjustment'
    adjustQuery.status = 'SUBMITTED'
    query.pageNo = 1
    load()
  } finally {
    adjustDialog.submitting = false
  }
}

const openReview = (row: any, approve: boolean) => {
  reviewDialog.row = row
  reviewDialog.approve = approve
  reviewDialog.comment = ''
  reviewDialog.visible = true
}

const submitReview = async () => {
  if (!reviewDialog.comment.trim()) {
    ElMessage.warning('请填写复核意见')
    return
  }
  reviewDialog.submitting = true
  try {
    await ZsApi.reviewManualAdjustment(reviewDialog.row.id, {
      approve: reviewDialog.approve,
      comment: reviewDialog.comment.trim()
    })
    ElMessage.success(reviewDialog.approve ? '复核通过，点数已调整' : '已驳回')
    reviewDialog.visible = false
    load()
  } finally {
    reviewDialog.submitting = false
  }
}

const adjustStatusText = (s: string) => ({ SUBMITTED: '待复核', EXECUTED: '已执行', REJECTED: '已驳回' }[s] || s)
const adjustStatusClass = (s: string) =>
  ({ SUBMITTED: 'zs-tag--yellow', EXECUTED: 'zs-tag--green', REJECTED: 'zs-tag--red' }[s] || '')

onMounted(load)
</script>

<style lang="scss" scoped>
.zs-filter {
  margin-bottom: 6px;
}

.zs-footnote {
  margin-top: 16px;
  padding: 12px 16px;
  background: #faf6f0;
  border-radius: 8px;
  font-size: 12px;
  color: #8a8a8a;
}
</style>
