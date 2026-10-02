<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div
        ><h1 class="zs-page-title">业务积分价格</h1
        ><div class="zs-page-subtitle"
          >预算按成功测算计费；提示词按模型实际调用计费。新价格仅影响新业务。</div
        ></div
      >
      <el-button v-if="canManage" type="primary" @click="openEditor()">新增价格</el-button>
    </div>
    <el-alert
      v-if="!canQuery"
      title="没有价格查询权限，请联系管理员"
      type="warning"
      :closable="false"
    />
    <div v-else class="zs-table-card">
      <el-alert
        title="平面图、立面图的 2K / 4K 价格在“生成价格”中单独设置。"
        type="info"
        :closable="false"
        class="mb-16px"
      />
      <el-form inline>
        <el-form-item label="业务"
          ><el-select v-model="filters.product" clearable placeholder="全部业务" @change="load"
            ><el-option label="预算测算" value="BUDGET_ESTIMATE" /><el-option
              label="提示词模型调用"
              value="AI_PROMPT" /></el-select
        ></el-form-item>
        <el-form-item label="状态"
          ><el-select v-model="filters.status" clearable placeholder="全部状态" @change="load"
            ><el-option label="生效中" value="ACTIVE" /><el-option
              label="已停用"
              value="RETIRED" /></el-select
        ></el-form-item>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </el-form>
      <el-alert
        v-if="loadError"
        title="价格加载失败，请重试"
        type="error"
        :closable="false"
        class="mb-16px"
      />
      <el-table v-loading="loading" :data="rows" stripe>
        <el-table-column label="业务" min-width="170"
          ><template #default="{ row }"
            >{{ label(row.product) }}
            <span v-if="isActiveNow(row)" class="zs-tag zs-tag--green zs-current-tag">现价</span>
          </template></el-table-column
        >
        <el-table-column label="价格（设计点 / 次）" prop="pointCost" width="160" />
        <el-table-column label="生效时间" min-width="170"
          ><template #default="{ row }">{{ fmtTime(row.effectiveAt) }}</template></el-table-column
        >
        <el-table-column label="失效时间" min-width="170"
          ><template #default="{ row }">{{
            row.expiresAt ? fmtTime(row.expiresAt) : '长期有效'
          }}</template></el-table-column
        >
        <el-table-column label="状态" width="100"
          ><template #default="{ row }">{{
            row.status === 'ACTIVE' ? '生效中' : '已停用'
          }}</template></el-table-column
        >
        <el-table-column label="操作" width="120"
          ><template #default="{ row }"
            ><el-button
              v-if="canManage && row.status === 'ACTIVE'"
              link
              type="primary"
              @click="openEditor(row)"
              >调价</el-button
            ><el-button
              v-if="canManage && row.status === 'ACTIVE'"
              link
              type="danger"
              @click="retire(row)"
              >停用</el-button
            ></template
          ></el-table-column
        >
      </el-table>
      <Pagination
        v-model:page="filters.pageNo"
        v-model:limit="filters.pageSize"
        :total="total"
        @pagination="load"
      />
    </div>
    <el-dialog
      v-model="editorOpen"
      title="新增业务价格"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form label-width="100px">
        <el-form-item label="计费业务"
          ><el-select v-model="form.product"
            ><el-option label="预算测算（每次成功）" value="BUDGET_ESTIMATE" /><el-option
              label="提示词模型调用（每次调用）"
              value="AI_PROMPT" /></el-select
        ></el-form-item>
        <el-form-item label="设计点 / 次"
          ><el-input-number v-model="form.pointCost" :min="1" :max="1000000000" :precision="0"
        /></el-form-item>
        <el-form-item label="生效时间"
          ><el-date-picker
            v-model="form.effectiveAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
        /></el-form-item>
        <el-form-item label="失效时间"
          ><el-date-picker
            v-model="form.expiresAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            placeholder="留空为长期有效"
        /></el-form-item>
      </el-form>
      <template #footer
        ><el-button :disabled="saving" @click="editorOpen = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="save">保存价格</el-button></template
      >
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import * as Api from '@/api/zs/usage-pricing'
import type { UsagePriceRule, UsageProduct } from '@/api/zs/usage-pricing'
import { checkPermi } from '@/utils/permission'
import { fmtTime } from '@/utils/zsFormat'
import { ElMessageBox } from 'element-plus'
defineOptions({ name: 'ZsUsagePricing' })
const message = useMessage()
const canQuery = computed(() => checkPermi(['commerce:usage-price:query']))
const canManage = computed(() => checkPermi(['commerce:usage-price:manage']))
const loading = ref(false),
  loadError = ref(false),
  saving = ref(false),
  editorOpen = ref(false)
const rows = ref<UsagePriceRule[]>([]),
  total = ref(0)
const filters = reactive({ product: '', status: '', pageNo: 1, pageSize: 20 })
const form = reactive({
  product: 'BUDGET_ESTIMATE' as UsageProduct,
  pointCost: undefined as number | undefined,
  effectiveAt: '',
  expiresAt: null as string | null
})
const label = (product: UsageProduct) =>
  product === 'BUDGET_ESTIMATE' ? '预算测算（成功一次）' : '提示词模型调用（每次）'
// D2-7 现价判定：ACTIVE 且当前时间处于生效窗口
const isActiveNow = (row: UsagePriceRule) => {
  if (row.status !== 'ACTIVE') return false
  const now = Date.now()
  return (
    new Date(row.effectiveAt).getTime() <= now &&
    (!row.expiresAt || new Date(row.expiresAt).getTime() > now)
  )
}
// 竞态保护：快速切换筛选时丢弃旧响应，避免旧结果覆盖新结果
let loadSeq = 0
async function load() {
  if (!canQuery.value) return
  const seq = ++loadSeq
  loading.value = true
  loadError.value = false
  try {
    const page = await Api.listRules({
      ...filters,
      product: filters.product || undefined,
      status: filters.status || undefined
    })
    if (seq !== loadSeq) return
    rows.value = page.list
    total.value = page.total
  } catch {
    if (seq !== loadSeq) return
    rows.value = []
    total.value = 0
    loadError.value = true
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}
function openEditor(row?: UsagePriceRule) {
  // D2-8 调价预填：从现价行进入时带出旧值（业务为"追加新价"，旧价保留至失效）
  Object.assign(form, {
    product: row?.product || 'BUDGET_ESTIMATE',
    pointCost: row?.pointCost,
    effectiveAt: new Date().toISOString(),
    expiresAt: null
  })
  editorOpen.value = true
}
async function save() {
  if (!canManage.value || saving.value) return
  if (
    !Number.isSafeInteger(form.pointCost) ||
    !form.pointCost ||
    !form.effectiveAt ||
    (form.expiresAt && new Date(form.expiresAt) <= new Date(form.effectiveAt))
  ) {
    message.warning('请填写有效价格和生效时间')
    return
  }
  saving.value = true
  try {
    await Api.createRule({ ...form, pointCost: form.pointCost })
    message.success('价格已新增')
    editorOpen.value = false
    await load()
  } catch (e: any) {
    message.error(e?.msg || '保存失败，请检查权限或时间范围')
  } finally {
    saving.value = false
  }
}
async function retire(row: UsagePriceRule) {
  if (!canManage.value || saving.value) return
  // D2-4 停用原因必填（留审计）；确认取消与接口失败分开处理——停用失败静默会让运营误以为价格已停用（仍在计费）
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `停用“${label(row.product)}”当前价格？停用后相关业务将无法按该价计费。`,
      '停用业务价格',
      {
        type: 'warning',
        confirmButtonText: '确认停用',
        cancelButtonText: '取消',
        inputPlaceholder: '停用原因（必填，将写入审计）',
        inputValidator: (v: string) => (v?.trim() ? true : '停用原因必填')
      }
    )
    reason = value.trim()
  } catch {
    return
  }
  saving.value = true
  try {
    await Api.retireRule(row.ruleId, { reason })
    message.success('价格已停用')
    await load()
  } catch (e: any) {
    message.error(e?.msg || '停用失败，请刷新后重试；该价格在停用成功前仍在计费')
  } finally {
    saving.value = false
  }
}
onMounted(load)
</script>

<style lang="scss" scoped>
.zs-current-tag {
  margin-left: 6px;
}
</style>
