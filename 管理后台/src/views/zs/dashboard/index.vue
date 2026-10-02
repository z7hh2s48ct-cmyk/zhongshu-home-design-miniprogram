<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">工作台</h1>
        <div class="zs-page-subtitle">掌握今天的设计与运营情况</div>
      </div>
      <div class="zs-date">{{ today }}</div>
    </div>

    <el-alert v-if="summaryError" type="error" :closable="false" title="统计加载失败，请重试"
      ><el-button @click="loadSummary">重试</el-button></el-alert
    >
    <!-- 统计卡 -->
    <div class="zs-stat-cards">
      <div class="zs-stat-card" v-for="card in statCards" :key="card.label">
        <div class="zs-stat-icon"><Icon :icon="card.icon" /></div>
        <div>
          <div class="zs-stat-label">{{ card.label }}</div>
          <div class="zs-stat-value">{{ summaryReady ? (card.value ?? '—') : '—' }}</div>
        </div>
      </div>
    </div>

    <el-row :gutter="16">
      <!-- 近7日趋势 -->
      <el-col :span="16">
        <div class="zs-table-card">
          <div class="zs-panel-title">近7日全部成功任务（北京时间）</div>
          <Echart v-if="trendDays.length" :options="trendOptions" :height="280" />
          <el-empty v-else description="暂无可用趋势统计" />
        </div>
      </el-col>
      <!-- 待办事项 -->
      <el-col :span="8">
        <div class="zs-table-card zs-todo">
          <div class="zs-panel-title">待办事项</div>
          <div class="zs-todo-item" v-for="item in todos" :key="item.label">
            <div class="zs-todo-left">
              <span class="zs-todo-dot" :style="{ background: item.color }"></span>
              <span>{{ item.label }}</span>
            </div>
            <div class="zs-todo-right">
              <span class="zs-todo-count" :style="{ color: item.color }">{{
                summaryReady ? (item.count ?? '—') : '—'
              }}</span>
              <span class="zs-link" @click="item.go()">去处理 ›</span>
            </div>
          </div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="mt-16px">
      <!-- 最近生成任务 -->
      <el-col :span="16">
        <div class="zs-table-card">
          <div class="zs-panel-title">最近生成任务</div>
          <el-alert v-if="jobsError" title="任务加载失败" type="error" :closable="false"
            ><el-button @click="loadJobs">重试</el-button></el-alert
          >
          <el-table :data="recentJobs" stripe>
            <el-table-column label="任务编号" prop="jobNo" width="160" />
            <el-table-column label="用户" prop="userName" />
            <el-table-column label="生成类型" prop="phaseText" />
            <el-table-column label="数量" prop="requestedCount" width="70" />
            <el-table-column label="扣点快照" prop="pointCost" width="100" />
            <el-table-column label="结算退点" prop="refundedPoints" width="100" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <span class="zs-tag" :class="'zs-tag--' + row.statusColor">{{
                  row.statusText
                }}</span>
              </template>
            </el-table-column>
            <el-table-column label="时间" prop="createTime" width="170" />
          </el-table>
          <div class="zs-link zs-view-all" @click="openJobs">查看全部 ›</div>
        </div>
      </el-col>
      <!-- 快捷操作 -->
      <el-col :span="8">
        <div class="zs-table-card zs-quick">
          <div class="zs-panel-title">快捷操作</div>
          <div class="zs-quick-grid">
            <div class="zs-quick-item" v-for="q in quickActions" :key="q.label" @click="q.go()">
              <Icon :icon="q.icon" :size="30" />
              <span>{{ q.label }}</span>
            </div>
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script lang="ts" setup>
import { Echart } from '@/components/Echart'
import * as ZsApi from '@/api/zs'
import { checkPermi } from '@/utils/permission'
import { fmtTime } from '@/utils/zsFormat'

defineOptions({ name: 'ZsDashboard' })

const today = new Date().toLocaleDateString('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: 'long',
  day: 'numeric'
})

const $router = useRouter()
const openJobs = () => $router.push('/zs/point-ledger?tab=jobs')
const summaryReady = ref(false)
const summaryError = ref(false)
const jobsError = ref(false)

const summary = ref<Record<string, any>>({
  aiJobsSucceededToday: 0,
  submissionsPendingReview: 0,
  pointsConsumedToday: 0,
  accessGrantsActive: 0,
  aiJobsRunning: 0,
  ordersPendingFulfillment: 0,
  ordersUnknownPayment: 0,
  casesPublished: 0,
  accountsActive: 0,
  openRefunds: 0
})
const statCards = computed(() => [
  {
    label: '今日全部成功任务',
    value: summary.value.aiJobsSucceededToday,
    delta: '',
    icon: 'ep:cpu'
  },
  {
    label: '待审核案例',
    value: summary.value.submissionsPendingReview,
    delta: '',
    icon: 'ep:document-checked'
  },
  {
    label: '今日生成扣点（不含退点）',
    value: summary.value.pointsConsumedToday,
    delta: '',
    icon: 'ep:wallet'
  },
  { label: '有效授权', value: summary.value.accessGrantsActive, delta: '', icon: 'ep:ticket' }
])

const todos = computed(() => [
  {
    label: 'AI案例待审核',
    count: summary.value.submissionsPendingReview,
    color: '#c07a1f',
    go: () => $router.push('/zs/review')
  },
  {
    label: '支付未到账',
    count: summary.value.ordersPendingFulfillment,
    color: '#d0342c',
    go: () => $router.push('/zs/recharge?tab=orders')
  },
  {
    label: '支付状态未知',
    count: summary.value.ordersUnknownPayment,
    color: '#d0342c',
    go: () => $router.push('/zs/recharge?tab=orders')
  },
  {
    label: '进行中任务',
    count: summary.value.aiJobsRunning,
    color: '#714320',
    go: openJobs
  }
])

const quickActions = computed(() => [
  { label: '新增公司案例', icon: 'ep:office-building', go: () => $router.push('/zs/case/create') },
  { label: '审核AI案例', icon: 'ep:document-checked', go: () => $router.push('/zs/review') },
  { label: '生成授权码', icon: 'ep:key', go: () => $router.push('/zs/access-code/batch') },
  { label: '新增充值方案', icon: 'ep:coin', go: () => $router.push('/zs/recharge-plan/edit') },
  ...(checkPermi(['design:budget:query'])
    ? [
        { label: '预算配置', icon: 'ep:money', go: () => $router.push('/zs/budget') },
        {
          label: '项目预算与报价',
          icon: 'ep:document',
          go: () => $router.push('/zs/budget-estimates')
        }
      ]
    : [])
])

const recentJobs = ref<any[]>([])

const trendOptions = computed((): any => ({
  grid: { left: 40, right: 20, top: 20, bottom: 30 },
  xAxis: { type: 'category', data: trendDays.value, boundaryGap: false },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    {
      type: 'line',
      data: trendValues.value,
      smooth: false,
      symbol: 'circle',
      symbolSize: 8,
      itemStyle: { color: '#714320' },
      lineStyle: { color: '#714320', width: 2 },
      areaStyle: { color: 'rgba(113, 67, 32, 0.06)' },
      label: { show: true, color: '#714320' }
    }
  ]
}))

const trendDays = ref<string[]>([])
const trendValues = ref<number[]>([])

const loadSummary = async () => {
  summaryError.value = false
  try {
    const res = await ZsApi.getDashboardSummary()
    if (!res) throw Error('统计为空')
    summary.value = res
    summaryReady.value = true
    trendDays.value = (res.aiTrend || []).map((d: any) => d.day)
    trendValues.value = (res.aiTrend || []).map((d: any) => Number(d.count))
  } catch {
    summaryError.value = true
    summaryReady.value = false
    trendDays.value = []
    trendValues.value = []
  }
}
const loadJobs = async () => {
  jobsError.value = false
  try {
    const res = await ZsApi.getAiJobPage({ pageNo: 1, pageSize: 5 })
    recentJobs.value = (res?.list || []).map((j: any) => ({
      jobNo: j.jobId,
      userName: j.userId ?? '—',
      phaseText: j.phase === 'FLAT' ? '平面方案生成' : '立面方案生成',
      requestedCount: j.requestedCount,
      pointCost: j.totalPointCost ?? '—',
      refundedPoints: j.refundedPointCost ?? '未结算',
      statusText:
        j.status === 'SUCCEEDED'
          ? '已完成'
          : j.status === 'RUNNING'
            ? '生成中'
            : j.status === 'FAILED'
              ? '已失败'
              : j.status,
      statusColor:
        j.status === 'SUCCEEDED'
          ? 'green'
          : j.status === 'RUNNING'
            ? 'orange'
            : j.status === 'FAILED'
              ? 'red'
              : 'gray',
      createTime: fmtTime(j.createdAt)
    }))
  } catch {
    jobsError.value = true
    recentJobs.value = []
  }
}
onMounted(() => Promise.all([loadSummary(), loadJobs()]))
</script>

<style lang="scss" scoped>
.zs-date {
  font-size: 14px;
  color: #6f6f6f;
}

.zs-panel-title {
  margin-bottom: 14px;
  font-size: 16px;
  font-weight: 700;
  color: #282728;
}

.zs-todo-item {
  display: flex;
  padding: 14px 0;
  font-size: 14px;
  color: #282728;
  border-bottom: 1px solid #f5f0e8;
  align-items: center;
  justify-content: space-between;

  &:last-child {
    border-bottom: none;
  }

  .zs-todo-left {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .zs-todo-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
  }

  .zs-todo-right {
    display: flex;
    align-items: center;
    gap: 14px;
  }

  .zs-todo-count {
    font-size: 18px;
    font-weight: 700;
  }
}

.zs-view-all {
  margin-top: 14px;
  font-size: 13px;
  text-align: center;
}

.zs-quick-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;

  .zs-quick-item {
    display: flex;
    padding: 22px 0;
    font-size: 14px;
    color: #714320;
    cursor: pointer;
    background: #faf6f0;
    border-radius: 10px;
    transition: all 0.2s;
    flex-direction: column;
    align-items: center;
    gap: 10px;

    &:hover {
      background: #f5eee7;
      transform: translateY(-2px);
    }
  }
}
</style>
