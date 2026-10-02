<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div v-if="!embedded">
        <h1 class="zs-page-title">充值订单</h1>
        <div class="zs-page-subtitle">支付事实与到账事实分列展示；异常订单可发起对账与整单退款</div>
      </div>
    </div>

    <div class="zs-table-card">
      <el-tabs v-model="activeTab" @tab-change="search">
        <el-tab-pane label="全部" name="ALL" />
        <el-tab-pane label="支付成功未到账" name="PAID_NO_CREDIT" />
        <el-tab-pane label="待支付" name="PENDING" />
        <el-tab-pane label="渠道退款单" name="REFUNDS" />
        <el-tab-pane label="渠道流水" name="TRANSACTIONS" />
      </el-tabs>

      <el-alert
        v-if="loadError"
        type="error"
        :closable="false"
        title="订单数据加载失败，请勿据此判断账务状态，请重试"
        style="margin-bottom: 12px"
      />

      <el-table
        v-if="activeTab !== 'REFUNDS' && activeTab !== 'TRANSACTIONS'"
        :data="list"
        v-loading="loading"
        stripe
      >
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
        <el-table-column label="到账状态" width="150">
          <template #default="{ row }">
            <span class="zs-tag" :class="fulColor(row.fulfillmentState)">{{
              fulText(row.fulfillmentState)
            }}</span>
            <span
              v-if="isPaidNoCredit(row)"
              class="zs-tag zs-tag--orange"
              title="支付成功但未到账，请主动查单或核对渠道流水"
              >未到账</span
            >
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="168">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="退款状态" width="110">
          <template #default="{ row }">{{
            row.refundState ? refundText(row.refundState) : '无退款'
          }}</template>
        </el-table-column>
        <el-table-column label="冲正状态" width="110">
          <template #default="{ row }">{{ reversalText(row.pointReversalState) }}</template>
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
            <span class="zs-link-danger" v-if="canRefund(row)" @click="doRefund(row)">{{
              refundingId === (row.orderId || row.id) ? '受理中…' : '整单退款'
            }}</span>
          </template>
        </el-table-column>
      </el-table>

      <el-table v-else-if="activeTab === 'REFUNDS'" :data="list" v-loading="loading" stripe>
        <el-table-column label="退款单号" prop="refundNo" width="180" />
        <el-table-column label="订单号" width="165">
          <template #default="{ row }"
            ><el-button link type="primary" @click="openDetail(row)">{{
              row.orderNo || row.orderId
            }}</el-button></template
          >
        </el-table-column>
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
        <el-table-column label="设计点冲正" width="120">
          <template #default="{ row }">{{ reversalText(row.pointReversalState) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="168">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>

      <el-table v-else :data="list" v-loading="loading" stripe>
        <el-table-column label="订单号" prop="orderNo" width="165" />
        <el-table-column label="支付渠道" width="110">
          <template #default="{ row }">{{ channelText(row.channel) }}</template>
        </el-table-column>
        <el-table-column label="商户号" prop="merchantId" width="150" show-overflow-tooltip />
        <el-table-column
          label="渠道流水号"
          prop="channelTransactionId"
          min-width="180"
          show-overflow-tooltip
        />
        <el-table-column label="金额(元)" width="100">
          <template #default="{ row }">{{ ((row.amountCents || 0) / 100).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="支付时间" width="168">
          <template #default="{ row }">{{ fmtTime(row.paidAt) }}</template>
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

    <el-dialog v-model="detail.visible" title="订单详情" width="560px">
      <el-descriptions v-if="detail.data" :column="2" border>
        <el-descriptions-item label="订单号">{{
          detail.data.orderNo || detail.data.orderId
        }}</el-descriptions-item>
        <el-descriptions-item label="用户">{{ detail.data.userId }}</el-descriptions-item>
        <el-descriptions-item label="金额(元)">{{
          ((detail.data.amountCents || 0) / 100).toFixed(2)
        }}</el-descriptions-item>
        <el-descriptions-item label="方案设计点（快照）">{{
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
        <el-descriptions-item v-if="detail.data.channel" label="支付渠道">{{
          channelText(detail.data.channel)
        }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.data.merchantId" label="商户号">{{
          detail.data.merchantId
        }}</el-descriptions-item>
        <el-descriptions-item
          v-if="detail.data.channelTransactionId"
          label="渠道流水号"
          :span="2"
          >{{ detail.data.channelTransactionId }}</el-descriptions-item
        >
        <el-descriptions-item label="创建时间">{{
          fmtTime(detail.data.createdAt)
        }}</el-descriptions-item>
        <el-descriptions-item label="退款状态">{{
          detail.data.refund ? refundText(detail.data.refund.channelState) : '无退款'
        }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.data.refund" label="退款金额"
          >¥{{ (detail.data.refund.amountCents / 100).toFixed(2) }}</el-descriptions-item
        >
        <el-descriptions-item v-if="detail.data.refund" label="退款编号">{{
          detail.data.refund.refundId
        }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.data.refund" label="设计点冲正">{{
          reversalText(detail.data.refund.pointReversalState)
        }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.data.refund" label="退款原因" :span="2">{{
          detail.data.refund.reason || '未填写'
        }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { fmtTime } from '@/utils/zsFormat'

defineOptions({ name: 'ZsRechargeOrder' })
// embedded=true 时由充值管理合并页内嵌，标题交给外层
defineProps<{ embedded?: boolean }>()
const message = useMessage()

const activeTab = ref('ALL')
const loading = ref(false)
/** 接口失败标记：用于把“加载失败”与“确实没有数据”区分开 */
const loadError = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 10 })
const detail = reactive({ visible: false, data: null as any })
// T13-30 前半 ①b：整单退款重复提交防护（后端 refund_request_key 幂等之外的前端守卫）
const refundingId = ref<string | null>(null)

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
  ({
    SUCCEEDED: 'zs-tag--green',
    PENDING: 'zs-tag--orange',
    FAILED: 'zs-tag--red',
    UNKNOWN: 'zs-tag--red'
  })[s] || 'zs-tag--gray'
const fulText = (s: string) =>
  ({ NOT_READY: '未到账', PENDING: '到账中', CREDITED: '已到账', FAILED: '到账失败' })[s] || s
const fulColor = (s: string) =>
  ({ CREDITED: 'zs-tag--green', FAILED: 'zs-tag--red', PENDING: 'zs-tag--orange' })[s] ||
  'zs-tag--gray'
const channelText = (s: string) =>
  ({ STUB: 'Stub(测试)', WECHAT: '微信支付', ALIPAY: '支付宝' })[s] || s || '—'
// T13-30 前半 ①c：支付成功但未到账（需人工对账）的高亮判定
const isPaidNoCredit = (row: any) =>
  row.paymentState === 'SUCCEEDED' && row.fulfillmentState !== 'CREDITED'
const refundText = (s: string) =>
  ({
    CREATED: '已创建',
    PENDING: '处理中',
    SUCCEEDED: '退款成功',
    FAILED: '退款失败',
    UNKNOWN: '确认中'
  })[s] || s

const canRefund = (row: any) =>
  row.paymentState === 'SUCCEEDED' &&
  row.fulfillmentState === 'CREDITED' &&
  (!row.refundState || row.refundState === 'FAILED')

const reversalText = (s?: string) =>
  ({ RESERVED: '已预留', REVERSED: '已冲正', RELEASED: '已释放' })[s || ''] || '—'

const search = () => {
  query.pageNo = 1
  return load()
}

const load = async () => {
  loading.value = true
  loadError.value = false
  try {
    if (activeTab.value === 'REFUNDS') {
      const res = await ZsApi.getRefundOrderPage({ ...query })
      list.value = res?.list || []
      total.value = res?.total || 0
      return
    }
    if (activeTab.value === 'TRANSACTIONS') {
      const res = await ZsApi.getPaymentTransactionPage({ ...query })
      list.value = res?.list || []
      total.value = res?.total || 0
      return
    }
    const params: any = { ...query }
    if (activeTab.value === 'PAID_NO_CREDIT') {
      params.paymentState = 'SUCCEEDED'
      params.paidNoCredit = true
    } else if (activeTab.value === 'PENDING') {
      params.paymentState = 'PENDING'
    }
    const res = await ZsApi.getOrderPage(params)
    list.value = res?.list || []
    total.value = res?.total || 0
  } catch {
    // 订单属财务流水：加载失败必须显式提示，不能被当成"没有订单"
    list.value = []
    total.value = 0
    loadError.value = true
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
  if (refundingId.value) return // 已有退款受理中，同步置位防双击重复提交
  const orderId = row.orderId || row.id
  refundingId.value = orderId
  try {
    await message.confirm(
      `确认为订单 ${row.orderNo} 受理整单退款？到账后若已有设计点消耗将被拒绝（P0 仅支持整单）。`,
      '整单退款'
    )
  } catch {
    refundingId.value = null
    return // 用户取消：静默中止，不算失败
  }
  try {
    await ZsApi.createRefundRequest(orderId, { reason: '管理端整单退款' })
    message.success('退款已受理')
    await load()
  } catch (e: any) {
    message.error(e?.msg || '退款失败')
  } finally {
    refundingId.value = null
  }
}

onMounted(load)
</script>
