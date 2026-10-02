<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">数据导出</h1>
        <div class="zs-page-subtitle"
          >异步导出：创建任务 → 后台生成 CSV → 凭一次性票据下载（24 小时有效）</div
        >
      </div>
    </div>

    <div class="zs-table-card">
      <el-form inline class="zs-filter">
        <el-form-item label="导出类型">
          <el-select v-model="form.jobType" style="width: 220px" @change="onJobTypeChange">
            <el-option label="设计点流水" value="POINT_LEDGER" />
            <el-option label="审计事件" value="AUDIT_EVENTS" />
            <el-option label="授权码批次交付记录" value="ACCESS_CODE_BATCHES" />
            <el-option label="C端用户清单" value="ACCOUNTS" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.jobType === 'POINT_LEDGER'" label="用户编号">
          <el-input v-model="form.userId" placeholder="可选，精确匹配" clearable />
        </el-form-item>
        <el-form-item v-if="typeOptions.length || form.jobType === 'AUDIT_EVENTS'" label="记录筛选">
          <el-select
            v-model="form.type"
            style="width: 200px"
            clearable
            filterable
            allow-create
            placeholder="可选，全部"
          >
            <el-option
              v-for="opt in typeOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="时间范围">
          <el-date-picker
            v-model="form.range"
            type="datetimerange"
            start-placeholder="开始（含）"
            end-placeholder="结束（不含）"
          />
        </el-form-item>
        <el-form-item>
          <el-button class="zs-btn-primary" :loading="creating" @click="create"
            >创建导出任务</el-button
          >
          <el-button @click="refreshAll">刷新列表</el-button>
        </el-form-item>
      </el-form>

      <el-alert v-if="error" type="error" :closable="false" :title="error" />
      <el-alert
        v-if="!jobs.length && !error && !loading"
        type="info"
        :closable="false"
        title="您尚无导出记录；创建后自动刷新状态，关闭重进仍可找回"
      />

      <el-table :data="jobs" v-loading="loading" stripe>
        <el-table-column label="任务号" prop="exportJobId" width="200" />
        <el-table-column label="类型" width="180">
          <template #default="{ row }">{{ typeText(row.jobType) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <span class="zs-tag" :class="statusClass(row.status)">{{
              statusTextMap[row.status] || row.status
            }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间（北京）" min-width="170"
          ><template #default="{ row }">{{ displayTime(row.createdAt) }}</template></el-table-column
        >
        <el-table-column label="失效时间（北京）" min-width="170"
          ><template #default="{ row }">{{ displayTime(row.expiresAt) }}</template></el-table-column
        >
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'PENDING' || row.status === 'RUNNING'"
              size="small"
              text
              type="primary"
              @click="refresh(row)"
              >刷新</el-button
            >
            <el-button
              v-if="row.status === 'COMPLETED'"
              size="small"
              type="success"
              plain
              :loading="row.downloading"
              @click="download(row)"
              >下载</el-button
            >
            <span v-if="row.status === 'FAILED'" style="font-size: 12px; color: #d0342c">{{
              exportError(row.error)
            }}</span>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="query.pageNo"
        v-model:page-size="query.pageSize"
        :total="total"
        layout="total, sizes, prev, pager, next"
        @size-change="search"
        @current-change="refreshAll"
      />

      <div class="zs-footnote">
        每次最多 10,000 条、10 MB，超限请缩小时间范围。导出截至任务创建时的数据；CSV 时间为
        UTC。下载票据 600 秒内单次有效，文件 24 小时后过期。
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { ElMessage } from 'element-plus'

defineOptions({ name: 'ZsExport' })

const loading = ref(false)
const creating = ref(false)
const jobs = ref<any[]>([])
const error = ref('')
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 20 })
let closed = false
let requestSequence = 0
let timer: ReturnType<typeof setTimeout> | undefined
const displayTime = (value: string) =>
  value
    ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
    : '—'

const form = reactive({ jobType: 'POINT_LEDGER', userId: '', type: '', range: [] as Date[] })
// 记录筛选按导出类型给出候选（此前为自由文本，要求背业务代码形同虚设）
const typeOptions = computed(() => {
  if (form.jobType === 'POINT_LEDGER') {
    return [
      { label: '充值基础点', value: 'RECHARGE_BASE_CREDIT' },
      { label: '充值赠送点', value: 'RECHARGE_BONUS_CREDIT' },
      { label: '平面生成扣点', value: 'FLAT_GENERATION_DEBIT' },
      { label: '立面生成扣点', value: 'ELEVATION_GENERATION_DEBIT' },
      { label: '结算退回', value: 'TASK_SETTLEMENT_REFUND' },
      { label: '人工调增', value: 'MANUAL_CREDIT' },
      { label: '人工调减', value: 'MANUAL_DEBIT' },
      { label: '退款冲正(基础)', value: 'RECHARGE_BASE_REVERSAL' },
      { label: '退款冲正(赠送)', value: 'RECHARGE_BONUS_REVERSAL' },
      { label: 'Budget estimate debit', value: 'BUDGET_ESTIMATE_DEBIT' },
      { label: 'AI prompt debit', value: 'AI_PROMPT_DEBIT' }
    ]
  }
  if (form.jobType === 'ACCOUNTS') {
    return [
      { label: '正常', value: 'ACTIVE' },
      { label: '已停用', value: 'DISABLED' },
      { label: '已注销', value: 'CLOSED' }
    ]
  }
  return []
})
const onJobTypeChange = () => {
  form.type = ''
  form.userId = ''
}
const exportError = (code: string) =>
  ({
    EXPORT_ROW_LIMIT: '记录超限，请缩小范围',
    EXPORT_SIZE_LIMIT: '文件超限，请缩小范围',
    EXPORT_RETRY_LIMIT: '重试次数超限，请重新创建'
  })[code] || '生成失败，请重新创建'

const typeText = (t: string) =>
  ({
    POINT_LEDGER: '设计点流水',
    AUDIT_EVENTS: '审计事件',
    ACCESS_CODE_BATCHES: '批次交付记录',
    ACCOUNTS: '用户清单'
  })[t] || t
const statusTextMap: Record<string, string> = {
  PENDING: '排队中',
  RUNNING: '生成中',
  COMPLETED: '已完成',
  FAILED: '失败',
  EXPIRED: '已过期'
}
const statusClass = (s: string) =>
  ({
    PENDING: 'zs-tag--yellow',
    RUNNING: 'zs-tag--yellow',
    COMPLETED: 'zs-tag--green',
    FAILED: 'zs-tag--red',
    EXPIRED: 'zs-tag--red'
  })[s] || ''

const create = async () => {
  if (creating.value) return
  creating.value = true
  try {
    await ZsApi.createExportJob({
      jobType: form.jobType,
      userId: form.jobType === 'POINT_LEDGER' ? form.userId.trim() : '',
      type: form.type.trim(),
      from: form.range?.[0]?.toISOString(),
      to: form.range?.[1]?.toISOString()
    })
    if (closed) return
    ElMessage.success('任务已创建，后台生成中')
    await search()
  } catch (e: any) {
    ElMessage.error(e?.msg || '创建失败，请检查筛选范围后重试')
  } finally {
    creating.value = false
  }
}

const refresh = async (job: any) => {
  try {
    const res = await ZsApi.getExportJob(job.exportJobId)
    if (!res || closed) return
    Object.assign(job, res)
    if (job.status === 'COMPLETED') ElMessage.success('导出完成，可下载')
  } catch {
    ElMessage.error('状态刷新失败，请重试')
  }
}

const refreshAll = async () => {
  const sequence = ++requestSequence
  clearTimeout(timer)
  loading.value = true
  error.value = ''
  try {
    const res = await ZsApi.getExportJobPage({ ...query })
    if (closed || sequence !== requestSequence) return
    jobs.value = res.list || []
    total.value = Number(res.total || 0)
    if (jobs.value.some((j) => ['PENDING', 'RUNNING'].includes(j.status)))
      timer = setTimeout(refreshAll, 3000)
  } catch {
    if (!closed && sequence === requestSequence)
      error.value = '导出记录加载失败，请点击刷新列表重试'
  } finally {
    if (!closed && sequence === requestSequence) loading.value = false
  }
}
const search = () => {
  query.pageNo = 1
  return refreshAll()
}
onMounted(refreshAll)
onBeforeUnmount(() => {
  closed = true
  requestSequence++
  clearTimeout(timer)
})

const download = async (job: any) => {
  if (job.downloading) return
  job.downloading = true
  try {
    const ticketRes = await ZsApi.createExportDownloadTicket(job.exportJobId)
    if (!ticketRes?.ticket) {
      ElMessage.error('下载票据申请失败')
      return
    }
    const blob = await ZsApi.downloadExportFile(job.exportJobId, ticketRes.ticket)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `export-${job.exportJobId}.csv`
    link.click()
    URL.revokeObjectURL(url)
  } catch {
    ElMessage.error('下载失败或文件已过期，请刷新状态后重试；重试会签发新票据')
  } finally {
    job.downloading = false
  }
}
</script>

<style lang="scss" scoped>
.zs-filter {
  margin-bottom: 6px;
}

.zs-footnote {
  padding: 12px 16px;
  margin-top: 16px;
  font-size: 12px;
  color: #8a8a8a;
  background: #faf6f0;
  border-radius: 8px;
}
</style>
