<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">设计点流水</h1>
        <div class="zs-page-subtitle"
          >只追加账本：充值、赠送、生成扣点、失败退回、人工调整全程留痕；人工调整单据见「用户与授权
          → 人工调点单」</div
        >
      </div>
    </div>

    <div class="zs-table-card">
      <el-alert
        v-if="loadError"
        type="error"
        :closable="false"
        title="点数台账加载失败，请重试后再判断账务"
        style="margin-bottom: 12px"
      />

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

      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column label="流水号" prop="ledgerId" width="180" />
        <el-table-column label="用户" prop="userId" width="110" />
        <el-table-column label="类型" width="140">
          <template #default="{ row }">
            <span class="zs-tag" :class="(row.delta || 0) > 0 ? 'zs-tag--green' : 'zs-tag--red'">{{
              typeText(row.type)
            }}</span>
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
        人工调增/调减流水由「人工调点单」制单并经双人复核后产生；已执行流水不可修改，只能反向调整。
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { fmtTime } from '@/utils/zsFormat'

defineOptions({ name: 'ZsPointLedger' })

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
</style>
