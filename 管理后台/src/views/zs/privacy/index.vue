<template>
  <div class="zs-page">
    <div class="zs-page-header"><h1 class="zs-page-title">隐私申请</h1></div>
    <el-alert
      type="info"
      :closable="false"
      title="关闭账号前须确认数据留存规则并处理余额、订单、退款与生成任务。关闭会吊销全部会话、撤回作品公开展示；账务和审计记录保留。"
    />
    <div class="zs-table-card">
      <el-button @click="load" :loading="loading">刷新申请</el-button>
      <el-alert v-if="error" type="error" :closable="false" :title="error" />
      <el-table :data="rows" v-loading="loading">
        <el-table-column prop="requestId" label="申请号" min-width="190" />
        <el-table-column prop="userId" label="用户编号" min-width="190" />
        <el-table-column label="申请类型" width="140"
          ><template #default="{ row }">{{
            row.requestType === 'EXPORT' ? '资料导出' : '关闭账号'
          }}</template></el-table-column
        >
        <el-table-column label="状态" width="120"
          ><template #default="{ row }">{{
            stateText[row.status] || row.status
          }}</template></el-table-column
        >
        <el-table-column label="申请时间" min-width="170"
          ><template #default="{ row }">{{ displayTime(row.createdAt) }}</template></el-table-column
        >
        <el-table-column label="处理" width="240"
          ><template #default="{ row }">
            <template v-if="row.requestType === 'CLOSE_ACCOUNT' && row.status === 'PENDING'">
              <el-button
                v-hasPermi="['identity:privacy:manage']"
                :disabled="busy"
                type="danger"
                text
                @click="decide(row.requestId, true)"
                >核对后关闭</el-button
              >
              <el-button
                v-hasPermi="['identity:privacy:manage']"
                :disabled="busy"
                text
                @click="decide(row.requestId, false)"
                >驳回并说明</el-button
              >
            </template>
            <span v-else>{{
              row.requestType === 'EXPORT' ? '自动导出，用户自行下载' : '处理已留审计记录'
            }}</span>
          </template></el-table-column
        >
      </el-table>
      <el-pagination
        v-model:current-page="query.pageNo"
        :page-size="20"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="load"
      />
    </div>
  </div>
</template>
<script setup lang="ts">
import * as api from '@/api/zs'
import { ElMessage, ElMessageBox } from 'element-plus'
defineOptions({ name: 'ZsPrivacy' })
const rows = ref<any[]>([])
const query = reactive({ pageNo: 1, pageSize: 20 })
const total = ref(0)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const stateText: Record<string, string> = {
  PENDING: '待处理',
  PROCESSING: '处理中',
  COMPLETED: '已完成',
  REJECTED: '未完成'
}
const displayTime = (value: string) =>
  value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) : '—'
let sequence = 0
let closed = false
const load = async () => {
  const seq = ++sequence
  loading.value = true
  error.value = ''
  try {
    const response = await api.getPrivacyRequestPage({ ...query })
    if (closed || seq !== sequence) return
    rows.value = response.list || []
    total.value = Number(response.total || 0)
  } catch {
    if (!closed && seq === sequence) error.value = '申请加载失败，请确认隐私处理权限后重试'
  } finally {
    if (!closed && seq === sequence) loading.value = false
  }
}
const decide = async (id: string, approve: boolean) => {
  if (busy.value) return
  busy.value = true
  try {
    const result = await ElMessageBox.prompt(
      approve
        ? '关闭账号将吊销全部会话并撤回公开展示。请记录核对结果及处理说明。'
        : '请填写驳回原因。',
      approve ? '确认关闭账号' : '处理隐私申请',
      {
        inputValidator: (value) =>
          (!!value?.trim() && value.length <= 512) || '请输入 1～512 字说明',
        confirmButtonText: '确认处理',
        cancelButtonText: '取消'
      }
    )
    if (closed) return
    await api.decidePrivacyRequest(id, { approve, reason: result.value.trim() })
    if (closed) return
    ElMessage.success('处理结果已保存')
    await load()
  } catch {
    // Cancellation is silent; API errors are displayed by the shared request interceptor.
  } finally {
    if (!closed) busy.value = false
  }
}
onMounted(load)
onBeforeUnmount(() => {
  closed = true
  sequence++
})
</script>
