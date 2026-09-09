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
          <el-select v-model="form.jobType" style="width: 220px">
            <el-option label="设计点流水（全量）" value="POINT_LEDGER" />
            <el-option label="审计事件（全量）" value="AUDIT_EVENTS" />
          </el-select>
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
            <span v-if="row.status === 'FAILED'" style="font-size: 12px; color: #d0342c"
              >生成失败</span
            >
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
        安全规则：导出文件下载需一次性票据（600 秒有效、单次消费、留审计）；文件本体 24 小时后过期。
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

const form = reactive({ jobType: 'POINT_LEDGER' })

const typeText = (t: string) => ({ POINT_LEDGER: '设计点流水', AUDIT_EVENTS: '审计事件' })[t] || t
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
    await ZsApi.createExportJob({ jobType: form.jobType })
    if (closed) return
    ElMessage.success('任务已创建，后台生成中')
    await search()
  } catch {
    ElMessage.error('创建失败，请重试')
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
