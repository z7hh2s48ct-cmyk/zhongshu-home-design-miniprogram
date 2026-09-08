<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">充值订单</h1>
        <div class="zs-page-subtitle">支付事实与到账事实分列展示；异常订单可发起对账与整单退款</div>
      </div>
    </div>

    <div class="zs-table-card">
      <el-tabs v-model="activeTab" @tab-change="load">
        <el-tab-pane label="全部" name="ALL" />
        <el-tab-pane label="支付成功未到账" name="PAID_NO_CREDIT" />
        <el-tab-pane label="待支付" name="PENDING" />
        <el-tab-pane label="渠道退款单" name="REFUNDS" />
      </el-tabs>

      <el-table v-if="activeTab !== 'REFUNDS'" :data="list" v-loading="loading" stripe>
        <el-table-column label="订单号" prop="orderNo" width="165" />
        <el-table-column label="用户" prop="userId" width="100" />
        <el-table-column label="金额(元)" width="88">
          <template #default="{ row }">{{ ((row.amountCents || 0) / 100).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="基础点" prop="basePoints" width="80" />
        <el-table-column label="赠送点" prop="bonusPoints" width="80" />
        <el-table-column label="支付状态" width="96">
          <template #default="{ row }">
            <span class="zs-tag" :class="payColor(row.paymentState)">{{
              payText(row.paymentState)
            }}</span>
          </template>
        </el-table-column>
        <el-table-column label="到账状态" width="96">
          <template #default="{ row }">
            <span class="zs-tag" :class="fulColor(row.fulfillmentState)">{{
              fulText(row.fulfillmentState)
            }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="168">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <span class="zs-link" @click="openDetail(row)">详情</span>
            <span
              class="zs-link"
              v-if="row.paymentState === 'PENDING' || row.paymentState === 'UNKNOWN'"
              @click="doReconcile(row)"
              >主动查单</span
            >
            <span class="zs-link-danger" v-if="canRefund(row)" @click="doRefund(row)"
              >整单退款</span
            >
          </template>
        </el-table-column>
      </el-table>

      <el-table v-else :data="list" v-loading="loading" stripe>
        <el-table-column label="退款单号" prop="refundNo" width="180" />
        <el-table-column label="订单号" prop="orderNo" width="165" />
        <el-table-column label="用户" prop="userId" width="100" />
        <el-table-column label="退款金额(元)" width="120">
          <template #default="{ row }">{{
            ((row.refundAmountCents || row.amountCents || 0) / 100).toFixed(2)
          }}</template>
        </el-table-column>
        <el-table-column label="渠道状态" width="120">
          <template #default="{ row }">
            <span
              class="zs-tag"
              :class="row.channelState === 'SUCCEEDED' ? 'zs-tag--green' : 'zs-tag--yellow'"
            >
              {{ refundText(row.channelState || row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="原因" prop="reason" min-width="160" show-overflow-tooltip />
        <el-table-column label="创建时间" width="168">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="mt-16px"
        layout="total, sizes, prev, pager, next"
        :total="total"
        :page-size="query.pageSize"
        v-model:current-page="query.pageNo"
        @current-change="load"
      />
    </div>

    <el-dialog v-model="detail.visible" title="订单详情" width="560px">
      <el-descriptions v-if="detail.data" :column="2" border>
        <el-descriptions-item label="订单号">{{
          detail.data.orderNo || detail.data.orderId
        }}</el-descriptions-item>
        <el-descriptions-item label="用户">{{ detail.data.userId }}</el-descriptions-item>
        <el-descriptions-item label="金额(元)">{{
          ((detail.data.amountCents || 0) / 100).toFixed(2)
        }}</el-descriptions-item>
        <el-descriptions-item label="到账设计点">{{
          (detail.data.basePoints || 0) + (detail.data.bonusPoints || 0)
        }}</el-descriptions-item>
        <el-descriptions-item label="支付状态">{{
          payText(detail.data.paymentState)
        }}</el-descriptions-item>
        <el-descriptions-item label="到账状态">{{
          fulText(detail.data.fulfillmentState)
        }}</el-descriptions-item>
        <el-descriptions-item label="支付时间">{{
          fmtTime(detail.data.paidAt)
        }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{
          fmtTime(detail.data.createdAt)
        }}</el-descriptions-item>
        <el-descriptions-item label="可执行动作" :span="2">{{
          (detail.data.allowedActions || []).join('、') || '—'
        }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { fmtTime } from '@/utils/zsFormat'

defineOptions({ name: 'ZsRechargeOrder' })
const message = useMessage()

const activeTab = ref('ALL')
const loading = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 10 })
const detail = reactive({ visible: false, data: null as any })

const payText = (s: string) =>
  ({
    CREATED: '已创建',
    PENDING: '待支付',
    SUCCEEDED: '已支付',
    CLOSED: '已关闭',
    FAILED: '失败',
    UNKNOWN: '未知'
  })[s] || s
const payColor = (s: string) =>
  ({ SUCCEEDED: 'green', PENDING: 'orange', FAILED: 'red', UNKNOWN: 'red' })[s] || 'gray'
const fulText = (s: string) =>
  ({ NOT_READY: '未到账', PENDING: '到账中', CREDITED: '已到账', FAILED: '到账失败' })[s] || s
const fulColor = (s: string) =>
  ({ CREDITED: 'green', FAILED: 'red', PENDING: 'orange' })[s] || 'gray'
const refundText = (s: string) =>
  ({
    CREATED: '已创建',
    PENDING: '处理中',
    SUCCEEDED: '退款成功',
    FAILED: '退款失败',
    UNKNOWN: '确认中'
  })[s] || s

const canRefund = (row: any) =>
  row.paymentState === 'SUCCEEDED' && row.fulfillmentState === 'CREDITED'

const load = async () => {
  loading.value = true
  try {
    if (activeTab.value === 'REFUNDS') {
      const res = await ZsApi.getRefundOrderPage({ ...query })
      list.value = res?.list || []
      total.value = res?.total || 0
      return
    }
    const params: any = { ...query }
    if (activeTab.value === 'PAID_NO_CREDIT') {
      params.paymentState = 'SUCCEEDED'
    } else if (activeTab.value === 'PENDING') {
      params.paymentState = 'PENDING'
    }
    const res = await ZsApi.getOrderPage(params)
    let items = res?.list || []
    if (activeTab.value === 'PAID_NO_CREDIT') {
      items = items.filter((o: any) => o.fulfillmentState !== 'CREDITED')
    }
    list.value = items
    total.value = res?.total || 0
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const openDetail = async (row: any) => {
  detail.data = await ZsApi.getOrder(row.orderId || row.id)
  detail.visible = true
}

const doReconcile = async (row: any) => {
  const res = await ZsApi.reconcileOrder(row.orderId || row.id, {})
  message.success(`查单结果：${res || '已处理'}`)
  load()
}

const doRefund = async (row: any) => {
  await message.confirm(
    `确认为订单 ${row.orderNo} 受理整单退款？到账后若已有设计点消耗将被拒绝（P0 仅支持整单）。`,
    '整单退款'
  )
  try {
    await ZsApi.createRefundRequest(row.orderId || row.id, { reason: '管理端整单退款' })
    message.success('退款已受理')
    load()
  } catch (e: any) {
    message.error(e?.msg || '退款失败')
  }
}

onMounted(load)
watch(activeTab, () => {
  query.pageNo = 1
  load()
})
</script>
