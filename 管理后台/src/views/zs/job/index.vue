<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">AI 任务</h1>
        <div class="zs-page-subtitle"
          >生成任务运行状态：排队/进行中/成功/失败；只读运维，不参与扣点与审核</div
        >
      </div>
    </div>

    <div class="zs-table-card">
      <el-form inline class="zs-filter">
        <el-form-item label="状态">
          <el-select
            v-model="query.status"
            clearable
            placeholder="全部"
            style="width: 180px"
            @change="search"
          >
            <el-option
              v-for="(label, value) in statusText"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="阶段">
          <el-select
            v-model="query.phase"
            clearable
            placeholder="全部"
            style="width: 150px"
            @change="search"
          >
            <el-option label="平面" value="FLAT" />
            <el-option label="立面" value="ELEVATION" />
          </el-select>
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
        title="AI 任务加载失败，请重试后再排查"
        style="margin-bottom: 12px"
      />

      <el-table :data="list" v-loading="loading" stripe @row-click="openDetail">
        <el-table-column label="任务号" prop="jobId" width="200" />
        <el-table-column label="阶段" width="100">
          <template #default="{ row }">{{ row.phase === 'ELEVATION' ? '立面' : '平面' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <span class="zs-tag" :class="statusClass(row.status)">{{
              statusText[row.status] || row.status
            }}</span>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="160">
          <template #default="{ row }">
            <el-progress :percentage="progressOf(row)" :stroke-width="8" />
          </template>
        </el-table-column>
        <el-table-column label="请求数" prop="requestedCount" width="90" />
        <el-table-column label="接受数" prop="acceptedCount" width="90" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click.stop="openDetail(row)"
              >详情</el-button
            >
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
    </div>

    <el-dialog v-model="detail.visible" title="任务详情" width="560px">
      <el-descriptions v-if="detail.data" :column="2" border>
        <el-descriptions-item label="任务号">{{ detail.data.jobId }}</el-descriptions-item>
        <el-descriptions-item label="阶段">{{
          detail.data.phase === 'ELEVATION' ? '立面' : '平面'
        }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{
          statusText[detail.data.status] || detail.data.status
        }}</el-descriptions-item>
        <el-descriptions-item label="进度">{{ detail.data.progress || 0 }}%</el-descriptions-item>
        <el-descriptions-item label="请求数量">{{
          detail.data.requestedCount
        }}</el-descriptions-item>
        <el-descriptions-item label="接受数量">{{
          detail.data.acceptedCount
        }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{
          fmtTime(detail.data.createdAt)
        }}</el-descriptions-item>
        <el-descriptions-item label="完成时间">{{
          fmtTime(detail.data.finishedAt)
        }}</el-descriptions-item>
        <el-descriptions-item label="生图单价（设计点/张）" :span="2">{{
          detail.data.unitPointCost ?? '—'
        }}</el-descriptions-item>
        <el-descriptions-item label="提示词调用（文本模型）">{{
          detail.data.promptPointCost ?? '未扣点'
        }}</el-descriptions-item>
        <el-descriptions-item label="生图扣点快照">{{
          detail.data.totalPointCost ?? '—'
        }}</el-descriptions-item>
        <el-descriptions-item label="结算退点">{{
          detail.data.refundedPointCost ?? '暂无结算'
        }}</el-descriptions-item>
        <el-descriptions-item label="结算净消耗（生图）">{{
          detail.data.netPointCost ?? '暂无结算'
        }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { fmtTime } from '@/utils/zsFormat'

defineOptions({ name: 'ZsAiJob' })

const loading = ref(false)
/** 接口失败标记：用于把“加载失败”与“确实没有数据”区分开 */
const loadError = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const query = reactive({ status: '', phase: '', pageNo: 1, pageSize: 10 })
const detail = reactive({ visible: false, data: null as any })

const statusText: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '生成中',
  VALIDATING: '校验中',
  CANCEL_REQUESTED: '取消中',
  SUCCEEDED: '已成功',
  PARTIALLY_SUCCEEDED: '部分成功',
  FAILED: '已失败',
  CANCELLED: '已取消'
}
const progressOf = (row: any) =>
  ['SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED', 'CANCELLED'].includes(row.status)
    ? 100
    : row.progress || 0
const statusClass = (s: string) =>
  ({
    QUEUED: 'zs-tag--yellow',
    RUNNING: 'zs-tag--yellow',
    VALIDATING: 'zs-tag--yellow',
    CANCEL_REQUESTED: 'zs-tag--yellow',
    SUCCEEDED: 'zs-tag--green',
    PARTIALLY_SUCCEEDED: 'zs-tag--green',
    FAILED: 'zs-tag--red',
    CANCELLED: 'zs-tag--red'
  })[s] || ''

const search = () => {
  query.pageNo = 1
  return load()
}

const load = async () => {
  loading.value = true
  loadError.value = false
  try {
    const res = await ZsApi.getAiJobPage({
      ...query,
      status: query.status || undefined,
      phase: query.phase || undefined
    })
    list.value = res?.list || []
    total.value = res?.total || 0
  } catch {
    // 加载失败必须与"没有任务"区分，避免排障时误判为队列为空
    list.value = []
    total.value = 0
    loadError.value = true
  } finally {
    loading.value = false
  }
}
const reset = () => {
  query.status = ''
  query.phase = ''
  query.pageNo = 1
  load()
}

const openDetail = async (row: any) => {
  detail.data = await ZsApi.getAiJob(row.jobId)
  detail.visible = true
}

onMounted(load)
</script>

<style lang="scss" scoped>
.zs-filter {
  margin-bottom: 6px;
}
</style>
