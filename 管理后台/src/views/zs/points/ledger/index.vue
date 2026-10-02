<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">点数流水</h1>
        <div class="zs-page-subtitle"
          >只追加账本：充值、赠送、生成扣点、失败退回、人工调整全程留痕；点行查看流水详情，生成任务并于一处</div
        >
      </div>
    </div>

    <div class="zs-table-card">
      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <el-tab-pane label="点数流水" name="ledger" />
        <el-tab-pane label="生成任务" name="jobs" />
      </el-tabs>

      <!-- ====== 点数流水 ====== -->
      <div v-if="activeTab === 'ledger'">
        <el-alert
          v-if="loadError"
          type="error"
          :closable="false"
          title="点数台账加载失败，请重试后再判断账务"
          style="margin-bottom: 12px"
        >
          <el-button @click="load">重新加载</el-button>
        </el-alert>

        <el-form inline class="zs-filter">
          <el-form-item label="用户">
            <el-input
              v-model="query.userId"
              placeholder="用户编号"
              clearable
              style="width: 160px"
              @keyup.enter="search"
            />
          </el-form-item>
          <el-form-item label="类型">
            <el-select
              v-model="query.type"
              clearable
              placeholder="全部"
              style="width: 180px"
              @change="search"
            >
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
            <el-button class="zs-btn-primary" @click="search">查询</el-button>
            <el-button @click="reset">重置</el-button>
          </el-form-item>
        </el-form>

        <el-table :data="list" v-loading="loading" stripe @row-click="openLedgerDetail">
          <el-table-column label="流水号" prop="ledgerId" width="180" />
          <el-table-column label="用户" prop="userId" width="110" />
          <el-table-column label="类型" width="140">
            <template #default="{ row }">
              <span
                class="zs-tag"
                :class="(row.delta || 0) > 0 ? 'zs-tag--green' : 'zs-tag--red'"
                >{{ typeText(row.type) }}</span
              >
            </template>
          </el-table-column>
          <el-table-column label="变化" width="90">
            <template #default="{ row }">
              <span
                :style="{ color: (row.delta || 0) > 0 ? '#3f9e56' : '#d0342c', fontWeight: 600 }"
              >
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
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <span class="zs-link" @click.stop="openLedgerDetail(row)">详情</span>
            </template>
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

        <div class="zs-footnote">
          点击行或「详情」查看该笔流水的完整来龙去脉；人工调整由「用户详情 →
          人工调点」制单并经双人复核后入账；已执行流水不可修改，只能反向调整。
        </div>
      </div>

      <!-- ====== 生成任务（原独立 AI 任务页并入） ====== -->
      <AiJob v-else embedded />
    </div>

    <!-- 流水详情 -->
    <el-dialog v-model="ledgerDetail.visible" title="流水详情" width="560px">
      <el-descriptions v-if="ledgerDetail.row" :column="2" border>
        <el-descriptions-item label="流水号">
          {{ ledgerDetail.row.ledgerId }}
        </el-descriptions-item>
        <el-descriptions-item label="用户编号">
          {{ ledgerDetail.row.userId }}
        </el-descriptions-item>
        <el-descriptions-item label="类型">
          {{ typeText(ledgerDetail.row.type) }}
        </el-descriptions-item>
        <el-descriptions-item label="变动点数">
          <span
            :style="{
              color: (ledgerDetail.row.delta || 0) > 0 ? '#3f9e56' : '#d0342c',
              fontWeight: 600
            }"
          >
            {{ (ledgerDetail.row.delta || 0) > 0 ? '+' : '' }}{{ ledgerDetail.row.delta }}
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="变动后可用余额">
          {{ ledgerDetail.row.balanceAfter }}
        </el-descriptions-item>
        <el-descriptions-item label="变动后预留">
          {{ ledgerDetail.row.reservedAfter ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="业务类型">
          {{ bizText(ledgerDetail.row.bizType) }}
        </el-descriptions-item>
        <el-descriptions-item label="业务编号">
          <span v-if="bizLinkOf(ledgerDetail.row)">
            <span class="zs-link" @click="$router.push(bizLinkOf(ledgerDetail.row)!.path)">
              {{ ledgerDetail.row.bizId }} →
            </span>
          </span>
          <span v-else>{{ ledgerDetail.row.bizId || '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="备注 / 原因" :span="2">
          {{ ledgerDetail.row.reason || '（无）' }}
        </el-descriptions-item>
        <el-descriptions-item label="发生时间" :span="2">
          {{ fmtTime(ledgerDetail.row.createdAt, true) }}
        </el-descriptions-item>
      </el-descriptions>
      <p class="zs-detail-hint">
        点数台账只追加不修改；同一业务键受幂等保护，重复请求不会重复入账。
      </p>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { fmtTime } from '@/utils/zsFormat'
import AiJob from '@/views/zs/job/index.vue'

defineOptions({ name: 'ZsPointLedger' })

const route = useRoute()
const activeTab = ref(route.query.tab === 'jobs' ? 'jobs' : 'ledger')
const onTabChange = () => {
  // 保持 URL 可分享/可回退（工作台“进行中任务”跳 ?tab=jobs）
  window.history.replaceState(
    null,
    '',
    activeTab.value === 'jobs' ? '?tab=jobs' : location.pathname
  )
}

const loading = ref(false)
/** 接口失败标记：用于把“加载失败”与“确实没有数据”区分开 */
const loadError = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const query = reactive({ userId: '', type: '', pageNo: 1, pageSize: 10 })

const bizText = (b: string) =>
  ({
    ai_job: 'AI任务',
    recharge_order: '充值订单',
    manual_point_adjustment: '人工调点',
    case_submission: '投稿',
    refund_order: '退款单',
    design_project: '设计项目',
    MANUAL_ADJUSTMENT: '人工调点'
  })[b] || b
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
  })[t] || t

// 流水 → 业务现场深链（与审计页同款映射；AI任务已并入本页 jobs tab）
const bizLinkOf = (row: any): { path: string } | null => {
  if (!row.bizType) return null
  const targets: Record<string, string> = {
    ai_job: '/zs/point-ledger?tab=jobs',
    recharge_order: '/zs/recharge?tab=orders',
    refund_order: '/zs/recharge?tab=orders',
    manual_point_adjustment: '/zs/point-adjustments',
    MANUAL_ADJUSTMENT: '/zs/point-adjustments'
  }
  return targets[row.bizType] ? { path: targets[row.bizType] } : null
}

// ---- 流水详情 ----
const ledgerDetail = reactive({ visible: false, row: null as any })
function openLedgerDetail(row: any) {
  ledgerDetail.row = row
  ledgerDetail.visible = true
}

const search = () => {
  query.pageNo = 1
  return load()
}

const load = async () => {
  loading.value = true
  loadError.value = false
  try {
    const res = await ZsApi.getPointLedgerPage({
      ...query,
      userId: query.userId || undefined,
      type: query.type || undefined
    })
    list.value = res?.list || []
    total.value = res?.total || 0
  } catch {
    // 点数台账属账务数据：加载失败必须显式提示，避免被读成"无流水"
    list.value = []
    total.value = 0
    loadError.value = true
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

onMounted(load)
</script>

<style lang="scss" scoped>
.zs-filter {
  margin-bottom: 6px;
}

.zs-footnote {
  padding: 12px 16px;
  margin-top: 16px;
  font-size: 12px;
  line-height: 1.9;
  color: #8a8a8a;
  background: #faf6f0;
  border-radius: 8px;
}

.zs-detail-hint {
  margin: 12px 0 0;
  font-size: 12px;
  color: #8a8a8a;
}
</style>
