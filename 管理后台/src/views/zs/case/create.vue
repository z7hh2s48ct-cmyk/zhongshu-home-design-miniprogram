<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">{{ isEdit ? '编辑公司案例' : '新增公司案例' }}</h1>
        <div class="zs-page-subtitle">参数校核通过后可上架至小程序户型库</div>
      </div>
      <el-button @click="$router.back()">返回</el-button>
    </div>

    <div class="zs-table-card zs-form">
      <el-form :model="form" label-width="110px" label-position="left">
        <el-form-item label="案例名称" required>
          <el-input v-model="form.title" placeholder="如：云栖雅院" maxlength="60" style="max-width: 420px" />
        </el-form-item>
        <el-form-item label="案例说明">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="设计亮点、户型说明等" maxlength="300" style="max-width: 640px" />
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
        <el-form-item label="封面图">
          <UploadImg v-model="form.coverUrl" />
        </el-form-item>
        <el-form-item label="平面图">
          <UploadImg v-model="form.planUrls" multiple />
        </el-form-item>
        <el-form-item label="立面图">
          <UploadImg v-model="form.elevationUrl" />
        </el-form-item>
        <el-form-item>
          <el-button class="zs-btn-primary" :loading="saving" @click="save(false)">保存草稿</el-button>
          <el-button :loading="saving" @click="saveAndPublish">保存并上架</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { UploadImg } from '@/components/UploadFile'

defineOptions({ name: 'ZsCaseCreate' })
const message = useMessage()
const route = useRoute()

const isEdit = computed(() => !!route.query.id)
const saving = ref(false)
const form = reactive({
  title: '',
  description: '',
  styleCode: 'NEW_CHINESE',
  floorCount: 2,
  buildingArea: 168,
  faceWidth: 12,
  depth: 10,
  coverUrl: '',
  planUrls: '' as any,
  elevationUrl: ''
})

const save = async (thenPublish?: boolean) => {
  if (!form.title) {
    message.error('请填写案例名称')
    return
  }
  saving.value = true
  try {
    let caseId: string
    if (isEdit.value) {
      caseId = String(route.query.id)
      await ZsApi.updateCase(caseId, { ...form, version: Number(route.query.version || 1) })
    } else {
      const res = await ZsApi.createCase({ ...form })
      caseId = res?.caseId
    }
    if (thenPublish) {
      await ZsApi.publishCase(caseId)
      message.success('已保存并上架')
    } else {
      message.success('草稿已保存')
    }
    route.query.id = caseId
  } catch (e: any) {
    message.error(e?.msg || '保存失败')
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
</style>
