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
          <el-select
            v-model="query.sourceType"
            clearable
            placeholder="全部"
            style="width: 120px"
            @change="search"
          >
            <el-option label="公司案例" value="COMPANY" />
            <el-option label="AI案例" value="AI" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="query.publicationStatus"
            clearable
            placeholder="全部"
            style="width: 120px"
            @change="search"
          >
            <el-option label="草稿" value="DRAFT" />
            <el-option label="已上架" value="PUBLISHED" />
            <el-option label="已下架" value="OFFLINE" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-input
            v-model="query.keyword"
            placeholder="案例名称"
            clearable
            style="width: 180px"
            @keyup.enter="search"
          />
        </el-form-item>
        <el-form-item>
          <el-button class="zs-btn-primary" @click="search">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 批量操作 -->
      <div class="zs-bulk" v-if="selectedIds.length">
        已选 {{ selectedIds.length }} 项：
        <el-button size="small" class="zs-btn-primary" @click="bulk(true)">批量上架</el-button>
        <el-button size="small" @click="bulk(false)">批量下架</el-button>
      </div>

      <el-alert
        v-if="loadError"
        type="error"
        :closable="false"
        title="案例列表加载失败，请重试（当前列表不代表完整案例库）"
        style="margin-bottom: 12px"
      />

      <!-- 表格 -->
      <el-table
        :data="list"
        v-loading="loading"
        stripe
        @selection-change="(rows) => (selectedIds = rows.map((r) => r.caseId))"
      >
        <el-table-column type="selection" width="44" />
        <el-table-column label="案例编号" prop="caseId" width="160">
          <template #default="{ row }">
            <span class="zs-link zs-case-link" @click="openDetail(row)">{{ row.caseId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="案例名称" prop="title" min-width="130">
          <template #default="{ row }">
            <span class="zs-link zs-case-link" @click="openDetail(row)">{{ row.title }}</span>
          </template>
        </el-table-column>
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
            <span class="zs-tag" :class="statusColor(row.publicationStatus)">{{
              statusText(row.publicationStatus)
            }}</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="165">
          <template #default="{ row }">{{ fmtTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <span
              v-if="row.sourceType === 'COMPANY'"
              class="zs-link"
              @click="$router.push(`/zs/case/create?id=${row.caseId}`)"
              >编辑</span
            >
            <span class="zs-link" @click="openPreview(row)">预览</span>
            <el-dropdown
              v-if="canMore(row)"
              trigger="click"
              @command="(command) => rowAction(command, row)"
            >
              <span class="zs-link zs-more">更多 ▾</span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                    v-if="
                      row.sourceType === 'COMPANY' &&
                      ['DRAFT', 'OFFLINE'].includes(row.publicationStatus)
                    "
                    command="publish"
                    >上架</el-dropdown-item
                  >
                  <el-dropdown-item v-if="row.publicationStatus === 'PUBLISHED'" command="offline"
                    >下架</el-dropdown-item
                  >
                </el-dropdown-menu>
              </template>
            </el-dropdown>
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
    <el-dialog
      v-model="preview.visible"
      title="案例预览"
      width="min(900px, 94vw)"
      @closed="clearPreview"
    >
      <div v-loading="preview.loading">
        <el-alert v-if="preview.error" type="error" :title="preview.error" :closable="false"
          ><el-button @click="openPreview({ caseId: preview.caseId })">重试</el-button></el-alert
        >
        <template v-if="preview.data">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="案例编号">{{ preview.data.caseId }}</el-descriptions-item>
            <el-descriptions-item label="名称">{{ preview.data.title }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{
              statusText(preview.data.publicationStatus)
            }}</el-descriptions-item>
            <el-descriptions-item label="建筑参数"
              >{{ preview.data.buildingArea }}㎡ /
              {{ preview.data.floorCount }}层</el-descriptions-item
            >
            <el-descriptions-item label="设计说明" :span="2">{{
              preview.data.description || '暂无设计说明'
            }}</el-descriptions-item>
          </el-descriptions>
          <div class="case-preview-grid">
            <section v-for="asset in preview.assets" :key="asset.label">
              <h3>{{ asset.label }}</h3>
              <el-image
                v-if="asset.url && !asset.error"
                :src="asset.url"
                fit="contain"
                :preview-src-list="[asset.url]"
                @error="asset.error = '图纸无法显示'"
              />
              <el-empty
                v-else
                :description="asset.loading ? '加载中…' : asset.error || '暂无该图纸'"
                :image-size="50"
              />
              <el-button v-if="asset.error" @click="loadPreviewAsset(asset, previewSequence)"
                >重试</el-button
              >
            </section>
          </div>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { fmtTime, styleText } from '@/utils/zsFormat'

defineOptions({ name: 'ZsCaseManage' })
const message = useMessage()
const $router = useRouter()

const loading = ref(false)
/** 接口失败标记：用于把“加载失败”与“确实没有数据”区分开 */
const loadError = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const selectedIds = ref<string[]>([])
const query = reactive({
  sourceType: '',
  publicationStatus: '',
  keyword: '',
  pageNo: 1,
  pageSize: 10
})
const preview = reactive({
  visible: false,
  loading: false,
  error: '',
  caseId: '',
  data: null as any,
  assets: [] as any[]
})
let previewSequence = 0
const clearPreview = () => {
  previewSequence++
  preview.assets.forEach((asset) => {
    if (asset.url) URL.revokeObjectURL(asset.url)
  })
  preview.assets = []
  preview.data = null
}
const loadPreviewAsset = async (asset: any, sequence: number) => {
  if (!asset.assetId || asset.loading) return
  asset.loading = true
  asset.error = ''
  try {
    const blob = await ZsApi.getCaseAsset(preview.caseId, asset.assetId)
    if (sequence !== previewSequence || !preview.visible) return
    if (asset.url) URL.revokeObjectURL(asset.url)
    asset.url = URL.createObjectURL(blob)
  } catch {
    if (sequence === previewSequence) asset.error = '图纸加载失败'
  } finally {
    asset.loading = false
  }
}
const openPreview = async (row: any) => {
  clearPreview()
  const sequence = previewSequence
  preview.caseId = String(row.caseId)
  preview.visible = true
  preview.loading = true
  preview.error = ''
  try {
    const detail = await ZsApi.getCase(preview.caseId)
    if (sequence !== previewSequence || !preview.visible) return
    preview.data = detail
    preview.assets = [
      { assetId: detail.coverAssetId, label: '封面' },
      { assetId: detail.elevationAssetId, label: '立面' },
      ...(detail.floorPlans || []).map((plan: any, index: number) => ({
        assetId: plan.assetId,
        label:
          detail.sourceType === 'COMPANY' && plan.floorNo != null
            ? `${plan.floorNo}层平面`
            : `平面方案 ${index + 1}`
      }))
    ]
    await Promise.all(preview.assets.map((asset) => loadPreviewAsset(asset, sequence)))
  } catch {
    if (sequence === previewSequence) preview.error = '案例加载失败，请重试'
  } finally {
    if (sequence === previewSequence) preview.loading = false
  }
}
onBeforeUnmount(clearPreview)

const statusText = (s: string) =>
  ({ DRAFT: '草稿', PUBLISHED: '已上架', OFFLINE: '已下架' })[s] || s
const statusColor = (s: string) =>
  ({ DRAFT: 'gray', PUBLISHED: 'green', OFFLINE: 'red' })[s] || 'gray'

const openDetail = (row: any) => $router.push(`/zs/case/detail/${row.caseId}`)
const canMore = (row: any) =>
  (row.sourceType === 'COMPANY' && ['DRAFT', 'OFFLINE'].includes(row.publicationStatus)) ||
  row.publicationStatus === 'PUBLISHED'
const rowAction = (command: string, row: any) => {
  if (command === 'publish') doPublish(row)
  else if (command === 'offline') doOffline(row)
}

const search = () => {
  query.pageNo = 1
  return load()
}

const load = async () => {
  loading.value = true
  loadError.value = false
  try {
    const res = await ZsApi.getCasePage({ ...query, keyword: query.keyword || undefined })
    list.value = res?.list || []
    total.value = res?.total || 0
  } catch {
    // 加载失败必须与"没有案例"区分，避免运营误以为案例库为空
    list.value = []
    total.value = 0
    loadError.value = true
  } finally {
    loading.value = false
  }
}
const reset = () => {
  query.pageNo = 1
  query.sourceType = ''
  query.publicationStatus = ''
  query.keyword = ''
  load()
}
const doPublish = async (row: any) => {
  if (row.sourceType !== 'COMPANY' || !['DRAFT', 'OFFLINE'].includes(row.publicationStatus)) return
  if ((await ZsApi.publishCase(row.caseId)) !== true) {
    message.error('状态已变化，请刷新列表')
    return load()
  }
  message.success('已上架')
  load()
}
const doOffline = async (row: any) => {
  await ZsApi.offlineCase(row.caseId, { reason: '管理端下架' })
  message.success('已下架')
  load()
}
const bulk = async (publish: boolean) => {
  const selected = list.value.filter((row) => selectedIds.value.includes(row.caseId))
  if (
    selected.length !== selectedIds.value.length ||
    selected.some((row) =>
      publish
        ? row.sourceType !== 'COMPANY' || !['DRAFT', 'OFFLINE'].includes(row.publicationStatus)
        : row.publicationStatus !== 'PUBLISHED'
    )
  ) {
    message.error(
      publish ? '请只选择草稿或下架的公司案例；AI案例须走投稿审核流程' : '请只选择已上架案例'
    )
    return
  }
  const res = await ZsApi.bulkCaseAction({
    caseIds: selectedIds.value,
    publish,
    reason: '批量操作'
  })
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
  display: flex;
  margin-bottom: 12px;
  font-size: 13px;
  color: #6f6f6f;
  align-items: center;
  gap: 10px;
}

.zs-link {
  margin-right: 12px;
}

.zs-case-link {
  font-weight: 600;
}

.zs-more {
  margin-right: 0;
}

.case-preview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 16px;
  margin-top: 16px;
}

.case-preview-grid .el-image {
  width: 100%;
  height: 280px;
}
</style>
