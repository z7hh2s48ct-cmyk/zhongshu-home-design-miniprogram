<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div
        ><h1 class="zs-page-title">项目预算</h1
        ><div class="zs-page-subtitle">分项预算与不可变修订；不在此发布对外报价</div></div
      >
      <el-button @click="$router.push('/zs/budget')">预算目录配置</el-button>
    </div>
    <el-alert
      v-if="!canQuery"
      title="没有预算查询权限，请联系管理员授权"
      type="warning"
      :closable="false"
    />
    <div v-else class="zs-table-card">
      <el-form inline @submit.prevent="search">
        <el-form-item label="项目编号"
          ><el-input
            v-model="projectId"
            clearable
            maxlength="19"
            placeholder="完整项目编号（精确）"
        /></el-form-item>
        <el-form-item label="关键词"
          ><el-input v-model="keyword" clearable placeholder="项目名 / 用户昵称（模糊）"
        /></el-form-item>
        <el-form-item label="完整性"
          ><el-select
            v-model="completeness"
            clearable
            placeholder="全部状态"
            class="estimate-select"
            ><el-option value="COMPLETE" label="完整" /><el-option
              value="INCOMPLETE"
              label="待补充" /></el-select
        ></el-form-item>
        <el-form-item
          ><el-button type="primary" :loading="loading" @click="search">查询</el-button
          ><el-button :disabled="loading" @click="reset">重置</el-button></el-form-item
        >
      </el-form>
      <el-alert
        v-if="loadError"
        :title="loadError"
        type="error"
        :closable="false"
        class="mb-16px"
      />
      <el-table v-loading="loading" :data="rows" stripe empty-text="暂无分项预算">
        <el-table-column label="预算 / 项目" min-width="220"
          ><template #default="{ row }"
            ><div>{{ row.budgetId }}</div
            ><small>{{ row.projectName || '未命名项目' }} · {{ row.projectId }}</small></template
          ></el-table-column
        >
        <el-table-column label="方案 / 地区" min-width="180"
          ><template #default="{ row }"
            ><div>{{
              row.schemeName ||
              (row.resultVersionId ? '方案版本 ' + row.resultVersionId : '项目级参数')
            }}</div
            ><small>{{ row.regionName || row.regionCode || '未选择地区' }}</small></template
          ></el-table-column
        >
        <el-table-column label="当前修订" width="100"
          ><template #default="{ row }">第 {{ row.revisionNo }} 版</template></el-table-column
        >
        <el-table-column label="状态" width="110"
          ><template #default="{ row }"
            ><el-tag :type="row.completeness === 'COMPLETE' ? 'success' : 'warning'">{{
              row.completeness === 'COMPLETE' ? '完整' : '待补充'
            }}</el-tag></template
          ></el-table-column
        >
        <el-table-column label="已计价小计（元）" min-width="150"
          ><template #default="{ row }">{{
            formatCents(row.pricedSubtotalCents)
          }}</template></el-table-column
        >
        <el-table-column label="完整总额（元）" min-width="140"
          ><template #default="{ row }">{{
            row.completeness === 'COMPLETE' && row.totalCents != null
              ? formatCents(row.totalCents)
              : '待补充，非完整总额'
          }}</template></el-table-column
        >
        <el-table-column label="用户已保存" width="105"
          ><template #default="{ row }">{{ row.saved ? '是' : '否' }}</template></el-table-column
        >
        <el-table-column label="生成时间" min-width="175"
          ><template #default="{ row }">{{ displayTime(row.createdAt) }}</template></el-table-column
        >
        <el-table-column label="操作" width="130" fixed="right"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="open(row)">详情</el-button
            ><el-button
              v-if="row.completeness === 'COMPLETE'"
              link
              type="primary"
              @click="openQuotes(row)"
              >对外报价</el-button
            ></template
          ></el-table-column
        >
      </el-table>
      <el-pagination
        v-model:current-page="pageNo"
        :page-size="20"
        :total="total"
        layout="total, prev, pager, next"
        class="mt-16px"
        @current-change="load"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import * as BudgetApi from '@/api/zs/budget-revision'
import type { BudgetSummary } from '@/api/zs/budget-revision'
import { checkPermi } from '@/utils/permission'
import { displayTime, errorText, formatCents, validId } from './revision-form'

defineOptions({ name: 'ZsBudgetEstimates' })
const router = useRouter()
const canQuery = computed(() => checkPermi(['design:budget:query']))
const rows = ref<BudgetSummary[]>([])
const projectId = ref(''),
  keyword = ref(''),
  completeness = ref(''),
  pageNo = ref(1),
  total = ref(0)
const loading = ref(false),
  loadError = ref('')
let sequence = 0
async function load() {
  const requestId = ++sequence
  rows.value = []
  total.value = 0
  loadError.value = ''
  loading.value = false
  if (!canQuery.value) return
  if (projectId.value && !validId(projectId.value)) {
    loadError.value = '请输入有效的完整项目编号'
    return
  }
  loading.value = true
  try {
    const response = await BudgetApi.getEstimates({
      pageNo: pageNo.value,
      pageSize: 20,
      ...(projectId.value ? { projectId: projectId.value } : {}),
      ...(keyword.value.trim() ? { keyword: keyword.value.trim() } : {}),
      ...(completeness.value ? { completeness: completeness.value } : {})
    })
    if (requestId === sequence && canQuery.value) {
      rows.value = response.list
      total.value = response.total
    }
  } catch (error) {
    if (requestId === sequence) loadError.value = errorText(error)
  } finally {
    if (requestId === sequence) loading.value = false
  }
}
function search() {
  pageNo.value = 1
  void load()
}
function reset() {
  projectId.value = ''
  keyword.value = ''
  completeness.value = ''
  search()
}
function open(row: BudgetSummary) {
  if (canQuery.value && validId(row.budgetId))
    void router.push('/zs/budget-estimates/' + row.budgetId)
}
// E-4 报价直达：省去进详情再点的两跳
function openQuotes(row: BudgetSummary) {
  if (canQuery.value && validId(row.budgetId))
    void router.push(`/zs/budget-estimates/${row.budgetId}/quotes`)
}
watch(canQuery, () => {
  void load()
})
onBeforeUnmount(() => {
  sequence++
})
onMounted(load)
</script>

<style scoped lang="scss">
.estimate-select {
  width: 180px;
}
</style>
