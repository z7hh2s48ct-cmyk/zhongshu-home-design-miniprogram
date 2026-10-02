<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">{{ isEdit ? '编辑充值方案' : '新增充值方案' }}</h1>
        <div class="zs-page-subtitle"
          >价格字段创建后如需调整，建议停用旧方案并新增（历史订单引用快照）</div
        >
      </div>
      <el-button @click="$router.back()">返回</el-button>
    </div>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
    <el-button v-if="loadError" @click="loadPlan">重新加载</el-button>
    <div class="zs-table-card zs-form" v-loading="loading">
      <el-form label-width="120px" label-position="left">
        <el-form-item label="方案名称" required>
          <el-input
            v-model="form.name"
            placeholder="如：体验包 50 元"
            maxlength="40"
            style="max-width: 360px"
          />
        </el-form-item>
        <el-form-item label="金额(元)" required>
          <el-input-number v-model="amountYuan" :min="1" :max="100000" :disabled="isEdit" />
        </el-form-item>
        <el-form-item label="基础设计点" required>
          <el-input-number
            v-model="form.basePoints"
            :min="0"
            :max="1000000"
            :step="100"
            :disabled="isEdit"
          />
        </el-form-item>
        <el-form-item label="赠送设计点">
          <el-input-number
            v-model="form.bonusPoints"
            :min="0"
            :max="1000000"
            :step="50"
            :disabled="isEdit"
          />
        </el-form-item>
        <el-form-item label="推荐位">
          <el-switch v-model="form.recommended" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="999" />
        </el-form-item>
        <el-form-item>
          <el-button
            class="zs-btn-primary"
            :loading="saving"
            :disabled="loading || !!loadError"
            @click="save"
            >保存</el-button
          >
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'

defineOptions({ name: 'ZsRechargePlanEdit' })
const message = useMessage()
const route = useRoute()
const isEdit = computed(() => !!route.query.id)
const saving = ref(false)

const loading = ref(false)
const loadError = ref('')
let original: Record<string, any> | null = null
const amountYuan = ref(50)
const form = reactive({
  name: '',
  basePoints: 500,
  bonusPoints: 50,
  recommended: false,
  sort: 0,
  enabled: true
})

const loadPlan = async () => {
  if (!isEdit.value) return
  loading.value = true
  loadError.value = ''
  original = null
  try {
    const plan = await ZsApi.getPlan(String(route.query.id))
    if (!plan || String(plan.id) !== String(route.query.id)) throw new Error('方案不存在')
    amountYuan.value = Number(plan.amountCents) / 100
    Object.assign(form, {
      name: plan.name,
      basePoints: plan.basePoints,
      bonusPoints: plan.bonusPoints,
      recommended: plan.recommended,
      sort: plan.sort,
      enabled: plan.enabled
    })
    original = { ...form }
  } catch (error: any) {
    loadError.value = error?.msg || error?.message || '加载方案失败，请重试'
  } finally {
    loading.value = false
  }
}
onMounted(loadPlan)

const save = async () => {
  if (loading.value || loadError.value || (isEdit.value && !original)) return
  if (!form.name) {
    message.error('请填写方案名称')
    return
  }
  saving.value = true
  try {
    if (isEdit.value) {
      const changes: Record<string, any> = {}
      for (const field of ['name', 'recommended', 'sort'] as const) {
        if (form[field] !== original![field]) changes[field] = form[field]
      }
      if (Object.keys(changes).length) await ZsApi.updatePlan(String(route.query.id), changes)
    } else {
      await ZsApi.createPlan({ ...form, amountCents: amountYuan.value * 100 })
    }
    message.success('已保存')
    history.back()
  } catch (e: any) {
    message.error(e?.msg || '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<style lang="scss" scoped>
.zs-form {
  max-width: 640px;
}
</style>
