<template>
  <div class="zs-page" v-loading="loading">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">案例详情</h1>
        <div class="zs-page-subtitle">{{ detail?.title || ' ' }}</div>
      </div>
      <div class="zs-actions">
        <el-button @click="$router.push('/zs/case')">返回列表</el-button>
        <el-button class="zs-btn-primary" @click="$router.push(`/zs/case/create?id=${caseId}`)"
          >编辑</el-button
        >
        <el-button
          v-if="
            detail?.sourceType === 'COMPANY' &&
            ['DRAFT', 'OFFLINE'].includes(detail?.publicationStatus)
          "
          type="success"
          plain
          @click="doPublish"
          >上架</el-button
        >
        <el-button
          v-if="detail?.publicationStatus === 'PUBLISHED'"
          type="danger"
          plain
          @click="doOffline"
          >下架</el-button
        >
      </div>
    </div>

    <div class="zs-table-card">
      <el-alert v-if="loadError" type="error" :title="loadError" :closable="false"
        ><el-button @click="load">重新加载</el-button></el-alert
      >
      <template v-if="detail">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="案例编号">{{ detail.caseId }}</el-descriptions-item>
          <el-descriptions-item label="名称">{{ detail.title }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <span class="zs-tag" :class="statusColor(detail.publicationStatus)">{{
              statusText(detail.publicationStatus)
            }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="来源">{{
            detail.sourceType === 'COMPANY' ? '公司案例' : 'AI案例'
          }}</el-descriptions-item>
          <el-descriptions-item label="风格">{{
            styleText(detail.styleCode)
          }}</el-descriptions-item>
          <el-descriptions-item label="建筑参数"
            >{{ detail.buildingArea }}㎡ / {{ detail.floorCount }}层</el-descriptions-item
          >
          <el-descriptions-item label="更新时间">{{
            detail.updatedAt ? fmtTime(detail.updatedAt) : '—'
          }}</el-descriptions-item>
          <el-descriptions-item label="设计说明" :span="2">{{
            detail.description || '暂无设计说明'
          }}</el-descriptions-item>
        </el-descriptions>

        <div class="case-preview-grid">
          <section v-for="asset in assets" :key="asset.label">
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
            <el-button v-if="asset.error" @click="loadAsset(asset)">重试</el-button>
          </section>
        </div>
        <p class="case-hint"
          >至少上传封面与平面图后才能上架；图片经一次性票据加载，不会暴露原始地址。</p
        >
      </template>
    </div>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { fmtTime, styleText } from '@/utils/zsFormat'

defineOptions({ name: 'ZsCaseDetail' })
const message = useMessage()
const route = useRoute()
const $router = useRouter()

const caseId = computed(() => String(route.params.caseId || ''))
const loading = ref(false)
const loadError = ref('')
const detail = ref<any>(null)
const assets = ref<any[]>([])
let sequence = 0

const statusText = (s: string) =>
  ({ DRAFT: '草稿', PUBLISHED: '已上架', OFFLINE: '已下架' })[s] || s
const statusColor = (s: string) =>
  ({ DRAFT: 'gray', PUBLISHED: 'green', OFFLINE: 'red' })[s] || 'gray'

const clearAssets = () => {
  sequence++
  assets.value.forEach((asset) => {
    if (asset.url) URL.revokeObjectURL(asset.url)
  })
  assets.value = []
}

const loadAsset = async (asset: any) => {
  if (!asset.assetId || asset.loading) return
  const seq = sequence
  asset.loading = true
  asset.error = ''
  try {
    const blob = await ZsApi.getCaseAsset(caseId.value, asset.assetId)
    if (seq !== sequence) return
    if (asset.url) URL.revokeObjectURL(asset.url)
    asset.url = URL.createObjectURL(blob)
  } catch {
    if (seq === sequence) asset.error = '图纸加载失败'
  } finally {
    if (seq === sequence) asset.loading = false
  }
}

const load = async () => {
  clearAssets()
  const seq = sequence
  loadError.value = ''
  loading.value = true
  try {
    const data = await ZsApi.getCase(caseId.value)
    if (seq !== sequence) return
    detail.value = data
    assets.value = [
      { assetId: data.coverAssetId, label: '封面图' },
      { assetId: data.elevationAssetId, label: '立面图' },
      ...(data.floorPlans || []).map((plan: any, index: number) => ({
        assetId: plan.assetId,
        label:
          data.sourceType === 'COMPANY' && plan.floorNo != null
            ? `${plan.floorNo}层平面图`
            : `平面方案 ${index + 1}`
      }))
    ]
    await Promise.all(assets.value.map((asset) => loadAsset(asset)))
  } catch (error: any) {
    if (seq === sequence) loadError.value = error?.msg || error?.message || '案例加载失败，请重试'
  } finally {
    if (seq === sequence) loading.value = false
  }
}

const doPublish = async () => {
  if ((await ZsApi.publishCase(caseId.value)) !== true) {
    message.error('状态已变化，请刷新后重试')
    return load()
  }
  message.success('已上架')
  load()
}
const doOffline = async () => {
  await ZsApi.offlineCase(caseId.value, { reason: '管理端下架' })
  message.success('已下架')
  load()
}

onMounted(load)
onBeforeUnmount(clearAssets)
</script>

<style lang="scss" scoped>
.case-preview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 16px;
  margin-top: 16px;
}

.case-preview-grid .el-image {
  width: 100%;
  height: 300px;
}

.case-hint {
  margin-top: 16px;
  font-size: 12px;
  color: #8a8a8a;
}
</style>
