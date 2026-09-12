<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">{{ isEdit ? '编辑公司案例' : '新增公司案例' }}</h1>
        <div class="zs-page-subtitle">参数校核通过后可上架至小程序户型库</div>
      </div>
      <el-button @click="$router.back()">返回</el-button>
    </div>

    <div class="zs-table-card zs-form" v-loading="loading">
      <el-alert v-if="loadError" :title="loadError" type="error" :closable="false"
        ><el-button @click="loadCase">重新加载</el-button></el-alert
      >
      <el-form :model="form" label-width="110px" label-position="left">
        <el-form-item label="案例名称" required>
          <el-input
            v-model="form.title"
            placeholder="如：云栖雅院"
            maxlength="60"
            style="max-width: 420px"
          />
        </el-form-item>
        <el-form-item label="案例说明">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="设计亮点、户型说明等"
            maxlength="300"
            style="max-width: 640px"
          />
        </el-form-item>
        <el-form-item label="风格" required>
          <el-select v-model="form.styleCode" style="width: 200px">
            <el-option label="新中式" value="NEW_CHINESE" />
            <el-option label="现代" value="MODERN" />
            <el-option label="欧式" value="EURO" />
            <el-option label="美式" value="AMERICAN" />
          </el-select>
        </el-form-item>
        <el-form-item label="层数" required>
          <el-input-number v-model="form.floorCount" :min="1" :max="4" />
        </el-form-item>
        <el-form-item label="建筑面积(㎡)" required>
          <el-input-number v-model="form.buildingArea" :min="30" :max="2000" />
        </el-form-item>
        <el-form-item label="面宽(m)">
          <el-input-number v-model="form.faceWidth" :min="4" :max="40" />
        </el-form-item>
        <el-form-item label="进深(m)">
          <el-input-number v-model="form.depth" :min="4" :max="40" />
        </el-form-item>
        <el-form-item label="案例图纸">
          <div class="case-images">
            <p
              >先保存草稿及楼层参数，再上传图纸。上传校验成功后立即保存到案例新版本；上架至少需要封面和平面图。</p
            >
            <p v-if="publicationStatus === 'PUBLISHED'"
              >此案例已上架，请先返回列表下架后再更换图纸。</p
            >
            <el-checkbox v-model="publicDisplay"
              >确认公司拥有图片使用权，并允许公开展示</el-checkbox
            >
            <el-checkbox v-model="generationReference"
              >另外允许这些新上传图片用作 AI 设计参考（可不选）</el-checkbox
            >
            <div v-for="slot in imageSlots" :key="slot.key" class="case-image-slot">
              <strong>{{ slot.label }}</strong>
              <el-image
                v-if="images[slot.key]?.url"
                :src="images[slot.key].url"
                fit="contain"
                :preview-src-list="[images[slot.key].url]"
              />
              <span v-else>{{
                images[slot.key]?.assetId ? '已关联，预览加载中或失败' : '尚未上传'
              }}</span>
              <el-button v-if="images[slot.key]?.error" text @click="previewImage(slot.key)"
                >重试预览</el-button
              >
              <input
                type="file"
                accept="image/jpeg,image/png"
                :aria-label="`上传${slot.label}`"
                :disabled="
                  !isEdit ||
                  !publicDisplay ||
                  uploading ||
                  saving ||
                  loading ||
                  !!loadError ||
                  loadedVersion == null ||
                  publicationStatus === 'PUBLISHED' ||
                  !checkPermi(['design:case:update'])
                "
                @change="uploadImage($event, slot)"
              />
            </div>
            <p>仅 JPG / PNG，单张不超过 16MB。缺图不使用示例图片代替。</p>
            <p v-if="uploading">正在上传并进行安全校验，请勿重复提交…</p>
            <el-alert v-if="uploadError" :title="uploadError" type="error" :closable="false" />
          </div>
        </el-form-item>
        <el-form-item>
          <el-button
            class="zs-btn-primary"
            :disabled="uploading || loading || !!loadError"
            :loading="saving"
            @click="save(false)"
            >保存草稿</el-button
          >
          <el-button
            :disabled="uploading || loading || !!loadError"
            :loading="saving"
            @click="saveAndPublish"
            >保存并上架</el-button
          >
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { checkPermi } from '@/utils/permission'

defineOptions({ name: 'ZsCaseCreate' })
const message = useMessage()
const route = useRoute()
const router = useRouter()

const isEdit = computed(() => !!route.query.id)
const saving = ref(false)
const loading = ref(false)
const loadError = ref('')
const loadedVersion = ref<number | null>(null)
const publicationStatus = ref('DRAFT')
const publicDisplay = ref(false)
const generationReference = ref(false)
const uploading = ref(false)
const uploadError = ref('')
const images = reactive<Record<string, { assetId: string; url: string; error: boolean }>>({})
const imageSlots = computed(() => [
  { key: 'COVER', role: 'COVER', label: '封面图', floorNo: null },
  { key: 'ELEVATION', role: 'ELEVATION', label: '立面图', floorNo: null },
  ...Array.from({ length: Math.min(4, Math.max(1, Number(form.floorCount) || 1)) }, (_, i) => ({
    key: `FLOOR_PLAN:${i + 1}`,
    role: 'FLOOR_PLAN',
    label: `${i + 1}层平面图`,
    floorNo: i + 1
  }))
])
let sequence = 0
const form = reactive({
  title: '',
  description: '',
  styleCode: 'NEW_CHINESE',
  floorCount: 2,
  buildingArea: 168,
  faceWidth: 12,
  depth: 10
})
const clearImages = () => {
  for (const key of Object.keys(images)) {
    if (images[key].url) URL.revokeObjectURL(images[key].url)
    delete images[key]
  }
}
const previewImage = async (key: string) => {
  const image = images[key]
  if (!image) return
  const seq = sequence
  image.error = false
  try {
    const blob = await ZsApi.getCaseAsset(String(route.query.id), image.assetId)
    if (seq !== sequence || images[key] !== image) return
    if (image.url) URL.revokeObjectURL(image.url)
    image.url = URL.createObjectURL(blob)
  } catch {
    if (seq === sequence && images[key] === image) image.error = true
  }
}
const uploadImage = async (
  event: Event,
  slot: { key: string; role: string; floorNo: number | null }
) => {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (
    !file ||
    uploading.value ||
    saving.value ||
    loading.value ||
    loadError.value ||
    !route.query.id ||
    loadedVersion.value == null
  )
    return
  if (
    !publicDisplay.value ||
    publicationStatus.value === 'PUBLISHED' ||
    !checkPermi(['design:case:update'])
  )
    return
  if (
    !['image/png', 'image/jpeg'].includes(file.type) ||
    file.size <= 0 ||
    file.size > 16 * 1024 * 1024
  ) {
    uploadError.value = '请选择不超过16MB的JPG或PNG图片'
    return
  }
  const seq = sequence
  const caseId = String(route.query.id)
  uploading.value = true
  uploadError.value = ''
  try {
    const data = new FormData()
    data.append('file', file)
    data.append('version', String(loadedVersion.value))
    data.append('role', slot.role)
    if (slot.floorNo != null) data.append('floorNo', String(slot.floorNo))
    data.append('publicDisplay', String(publicDisplay.value))
    data.append('generationReference', String(generationReference.value))
    const result = await ZsApi.uploadCaseImage(caseId, data)
    if (seq !== sequence || String(route.query.id) !== caseId) return
    loadedVersion.value = Number(result.version)
    if (images[slot.key]?.url) URL.revokeObjectURL(images[slot.key].url)
    images[slot.key] = { assetId: result.assetId, url: '', error: false }
    await previewImage(slot.key)
    message.success('图纸已校验并保存到案例')
  } catch (error: any) {
    if (seq === sequence)
      uploadError.value =
        (error?.msg || error?.message || '上传失败') +
        '；如响应丢失或版本冲突，请保留参数后重新加载案例核对，勿重复上传。'
  } finally {
    if (seq === sequence) uploading.value = false
  }
}
const loadCase = async () => {
  const seq = ++sequence
  loadError.value = ''
  loadedVersion.value = null
  if (!route.query.id) return
  loading.value = true
  try {
    const detail = await ZsApi.getCase(String(route.query.id))
    if (seq !== sequence) return
    if (detail.sourceType !== 'COMPANY') throw Error('AI案例只能通过投稿审核流程处理')
    if (detail.publicationStatus === 'PUBLISHED') throw Error('已上架案例请先返回列表下架后再编辑')
    for (const key of [
      'title',
      'description',
      'styleCode',
      'floorCount',
      'buildingArea',
      'faceWidth',
      'depth'
    ])
      form[key] = detail[key]
    loadedVersion.value = Number(detail.version)
    publicationStatus.value = detail.publicationStatus || 'DRAFT'
    clearImages()
    const addImage = (key: string, assetId?: string) => {
      if (assetId) images[key] = { assetId, url: '', error: false }
    }
    addImage('COVER', detail.coverAssetId)
    addImage('ELEVATION', detail.elevationAssetId)
    for (const plan of detail.floorPlans || []) addImage(`FLOOR_PLAN:${plan.floorNo}`, plan.assetId)
    await Promise.all(Object.keys(images).map(previewImage))
  } catch (error: any) {
    if (seq === sequence) loadError.value = error?.msg || error?.message || '案例加载失败'
  } finally {
    if (seq === sequence) loading.value = false
  }
}
onMounted(loadCase)
onBeforeUnmount(() => {
  sequence++
  clearImages()
})

const save = async (thenPublish?: boolean) => {
  if (
    saving.value ||
    uploading.value ||
    loading.value ||
    loadError.value ||
    (isEdit.value && loadedVersion.value == null)
  )
    return
  if (!form.title) {
    message.error('请填写案例名称')
    return
  }
  saving.value = true
  try {
    let caseId: string
    if (isEdit.value) {
      caseId = String(route.query.id)
      const saved = await ZsApi.updateCase(caseId, { ...form, version: loadedVersion.value })
      loadedVersion.value = Number(saved.version)
    } else {
      const res = await ZsApi.createCase({ ...form })
      caseId = res?.caseId
      loadedVersion.value = Number(res.version)
      await router.replace({ path: route.path, query: { ...route.query, id: caseId } })
    }
    if (thenPublish) {
      if ((await ZsApi.publishCase(caseId)) !== true)
        throw Error('已保存，但上架状态未变化，请返回列表核对')
      message.success('已保存并上架')
      publicationStatus.value = 'PUBLISHED'
    } else {
      message.success('草稿已保存')
    }
  } catch (e: any) {
    message.error(e?.msg || e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}
const saveAndPublish = () => save(true)
</script>

<style lang="scss" scoped>
.zs-form {
  max-width: 760px;
}

.case-images {
  width: 100%;
}

.case-images p {
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.case-images .el-checkbox {
  display: flex;
  height: auto;
  margin: 12px 0;
  white-space: normal;
}

.case-image-slot {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  margin: 16px 0;
}

.case-image-slot .el-image {
  width: 100%;
  height: 180px;
  max-width: 360px;
}

.case-image-slot input {
  max-width: 100%;
}
</style>
