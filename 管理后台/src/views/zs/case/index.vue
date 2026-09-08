<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">户型库管理</h1>
        <div class="zs-page-subtitle">公司案例与已发布的AI案例，统一沉淀为设计资产</div>
      </div>
      <div class="zs-actions">
        <el-button class="zs-btn-primary" @click="$router.push('/zs/case/create')">
          <Icon icon="ep:plus" class="mr-4px" /> 新增公司案例
        </el-button>
      </div>
    </div>

    <div class="zs-table-card">
      <!-- 筛选 -->
      <el-form inline class="zs-filter">
        <el-form-item label="来源">
          <el-select v-model="query.sourceType" clearable placeholder="全部" style="width: 120px" @change="load">
            <el-option label="公司案例" value="COMPANY" />
            <el-option label="AI案例" value="AI" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.publicationStatus" clearable placeholder="全部" style="width: 120px" @change="load">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="已上架" value="PUBLISHED" />
            <el-option label="已下架" value="OFFLINE" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="query.keyword" placeholder="案例名称" clearable style="width: 180px" @keyup.enter="load" />
        </el-form-item>
        <el-form-item>
          <el-button class="zs-btn-primary" @click="load">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 批量操作 -->
      <div class="zs-bulk" v-if="selectedIds.length">
        已选 {{ selectedIds.length }} 项：
        <el-button size="small" class="zs-btn-primary" @click="bulk(true)">批量上架</el-button>
        <el-button size="small" @click="bulk(false)">批量下架</el-button>
      </div>

      <!-- 表格 -->
      <el-table :data="list" v-loading="loading" stripe @selection-change="(rows) => (selectedIds = rows.map((r) => r.caseId))">
        <el-table-column type="selection" width="44" />
        <el-table-column label="案例编号" prop="caseId" width="160" />
        <el-table-column label="案例名称" prop="title" min-width="130" />
        <el-table-column label="来源" width="80">
          <template #default="{ row }">{{ row.sourceType === 'COMPANY' ? '公司' : 'AI' }}</template>
        </el-table-column>
        <el-table-column label="风格" width="100">
          <template #default="{ row }">{{ styleText(row.styleCode) }}</template>
        </el-table-column>
        <el-table-column label="层数" prop="floorCount" width="64" />
        <el-table-column label="面积(㎡)" prop="buildingArea" width="84" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <span class="zs-tag" :class="statusColor(row.publicationStatus)">{{ statusText(row.publicationStatus) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="165">
          <template #default="{ row }">{{ fmtTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <span class="zs-link" @click="$router.push(`/zs/case/create?id=${row.caseId}`)">编辑</span>
            <span class="zs-link" v-if="row.publicationStatus !== 'PUBLISHED'" @click="doPublish(row)">上架</span>
            <span class="zs-link-danger" v-if="row.publicationStatus === 'PUBLISHED'" @click="doOffline(row)">下架</span>
            <span class="zs-link" @click="$router.push(`/zs/review?id=${row.caseId}`)">预览</span>
          </template>
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
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { fmtTime, styleText } from '@/utils/zsFormat'

defineOptions({ name: 'ZsCaseManage' })
const message = useMessage()

const loading = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const selectedIds = ref<string[]>([])
const query = reactive({ sourceType: '', publicationStatus: '', keyword: '', pageNo: 1, pageSize: 10 })

const statusText = (s: string) => ({ DRAFT: '草稿', PUBLISHED: '已上架', OFFLINE: '已下架' }[s] || s)
const statusColor = (s: string) => ({ DRAFT: 'gray', PUBLISHED: 'green', OFFLINE: 'red' }[s] || 'gray')

const load = async () => {
  loading.value = true
  try {
    const res = await ZsApi.getCasePage({ ...query, keyword: query.keyword || undefined })
    list.value = res?.list || []
    total.value = res?.total || 0
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}
const reset = () => {
  query.sourceType = ''
  query.publicationStatus = ''
  query.keyword = ''
  load()
}
const doPublish = async (row: any) => {
  await ZsApi.publishCase(row.caseId)
  message.success('已上架')
  load()
}
const doOffline = async (row: any) => {
  await ZsApi.offlineCase(row.caseId, { reason: '管理端下架' })
  message.success('已下架')
  load()
}
const bulk = async (publish: boolean) => {
  const res = await ZsApi.bulkCaseAction({ caseIds: selectedIds.value, publish, reason: '批量操作' })
  const okCount = (res?.items || []).filter((i: any) => i.success).length
  message.info(`批量完成：成功 ${okCount} / ${selectedIds.value.length}`)
  load()
}
onMounted(load)
</script>

<style lang="scss" scoped>
.zs-filter {
  margin-bottom: 6px;
}

.zs-bulk {
  margin-bottom: 12px;
  font-size: 13px;
  color: #6f6f6f;
  display: flex;
  align-items: center;
  gap: 10px;
}

.zs-link {
  margin-right: 12px;
}
</style>
