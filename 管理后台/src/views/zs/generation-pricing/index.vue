<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">生成价格</h1>
        <div class="zs-page-subtitle">按平面、立面及 2K / 4K 分别定价；横竖画幅同价</div>
      </div>
      <el-button v-if="canManage" class="zs-btn-primary" @click="openEditor()">新增价格</el-button>
    </div>
    <el-alert
      v-if="!canQuery"
      title="没有价格查询权限，请联系管理员"
      type="warning"
      :closable="false"
    />
    <div v-else class="zs-table-card">
      <el-alert
        title="新价格到达生效时间后用于新任务，已创建任务保持原价。4K 未配置有效价格时不可生成。"
        type="info"
        :closable="false"
        class="mb-16px"
      />
      <el-form inline>
        <el-form-item label="阶段">
          <el-select
            v-model="filters.stage"
            clearable
            placeholder="全部阶段"
            class="w-160px"
            @change="refresh"
          >
            <el-option label="平面方案" value="FLAT" /><el-option
              label="立面方案"
              value="ELEVATION"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="清晰度">
          <el-select
            v-model="filters.resolution"
            clearable
            placeholder="全部档位"
            class="w-160px"
            @change="refresh"
          >
            <el-option label="2K" value="2K" /><el-option label="4K" value="4K" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="filters.status"
            clearable
            placeholder="全部状态"
            class="w-160px"
            @change="refresh"
          >
            <el-option label="启用" value="ACTIVE" /><el-option label="已停用" value="RETIRED" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button :loading="loading" @click="load">刷新</el-button></el-form-item>
      </el-form>
      <el-alert
        v-if="loadError"
        title="价格加载失败，请重试"
        type="error"
        :closable="false"
        class="mb-16px"
      />
      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column label="阶段" width="140"
          ><template #default="{ row }"
            >{{ stageLabel(row.stage) }}
            <span v-if="isActiveNow(row)" class="zs-tag zs-tag--green zs-current-tag">现价</span>
          </template></el-table-column
        >
        <el-table-column label="清晰度" prop="resolution" width="90" />
        <el-table-column label="设计点 / 张" prop="unitPointCost" width="130" />
        <el-table-column label="允许张数" width="110"
          ><template #default="{ row }"
            >{{ row.minCount }}–{{ row.maxCount }}</template
          ></el-table-column
        >
        <el-table-column label="生效时间" min-width="160"
          ><template #default="{ row }">{{ fmtTime(row.effectiveAt) }}</template></el-table-column
        >
        <el-table-column label="失效时间" min-width="160"
          ><template #default="{ row }">{{
            row.expiresAt ? fmtTime(row.expiresAt) : '长期有效'
          }}</template></el-table-column
        >
        <el-table-column label="状态" width="100"
          ><template #default="{ row }">{{
            row.status === 'ACTIVE' ? '启用' : '已停用'
          }}</template></el-table-column
        >
        <el-table-column label="操作" width="130"
          ><template #default="{ row }">
            <el-button v-if="canManage" link type="primary" @click="openEditor(row)"
              >调价</el-button
            >
            <el-button
              v-if="canManage && row.status === 'ACTIVE'"
              link
              type="danger"
              :disabled="saving"
              @click="retire(row)"
              >停用</el-button
            >
            <el-button
              v-if="canManage && row.status !== 'ACTIVE'"
              link
              type="danger"
              :disabled="saving"
              @click="remove(row)"
              >删除</el-button
            >
          </template></el-table-column
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
      :title="
        editingSource
          ? `调价 · ${stageLabel(form.stage)} ${form.resolution}（生成新价格版本，旧价保留至失效）`
          : '新增生成价格'
      "
      width="500px"
      :close-on-click-modal="!saving"
    >
      <el-form label-width="100px" :disabled="saving">
        <el-form-item label="生成阶段"
          ><el-radio-group v-model="form.stage"
            ><el-radio-button value="FLAT">平面</el-radio-button
            ><el-radio-button value="ELEVATION">立面</el-radio-button></el-radio-group
          ></el-form-item
        >
        <el-form-item label="清晰度"
          ><el-radio-group v-model="form.resolution"
            ><el-radio-button value="2K">2K</el-radio-button
            ><el-radio-button value="4K">4K</el-radio-button></el-radio-group
          ></el-form-item
        >
        <el-form-item label="单张设计点" required
          ><el-input-number v-model="form.unitPointCost" :min="1" :max="1000000000" :precision="0"
        /></el-form-item>
        <el-form-item label="最少张数"
          ><el-input-number v-model="form.minCount" :min="1" :max="4" :precision="0"
        /></el-form-item>
        <el-form-item label="最多张数"
          ><el-input-number v-model="form.maxCount" :min="1" :max="4" :precision="0"
        /></el-form-item>
        <el-form-item label="生效时间" required
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
import * as PricingApi from '@/api/zs/generation-pricing'
import type { GenerationPriceRule, CreateGenerationPrice } from '@/api/zs/generation-pricing'
import { checkPermi } from '@/utils/permission'
import { fmtTime } from '@/utils/zsFormat'
import { ElMessageBox } from 'element-plus'

defineOptions({ name: 'ZsGenerationPricing' })
const message = useMessage()
const canQuery = computed(() => checkPermi(['commerce:generation-price:query']))
const canManage = computed(() => checkPermi(['commerce:generation-price:manage']))
const loading = ref(false)
const loadError = ref(false)
const saving = ref(false)
const editorOpen = ref(false)
const list = ref<GenerationPriceRule[]>([])
const total = ref(0)
const filters = reactive({ stage: '', resolution: '', status: '', pageNo: 1, pageSize: 20 })
const form = reactive({
  stage: 'FLAT',
  resolution: '2K',
  unitPointCost: undefined as number | undefined,
  minCount: 1,
  maxCount: 4,
  effectiveAt: '',
  expiresAt: ''
})
// 调价来源行：非空表示从已有价格进入（生成新版本而非覆盖）
const editingSource = ref<GenerationPriceRule | null>(null)
const stageLabel = (stage: string) => (stage === 'FLAT' ? '平面方案' : '立面方案')
// D2-7 现价判定：ACTIVE 且当前时间处于生效窗口
const isActiveNow = (row: GenerationPriceRule) => {
  if (row.status !== 'ACTIVE') return false
  const now = Date.now()
  return (
    new Date(row.effectiveAt).getTime() <= now &&
    (!row.expiresAt || new Date(row.expiresAt).getTime() > now)
  )
}
let loadSequence = 0
async function load() {
  if (!canQuery.value) return
  const sequence = ++loadSequence
  loading.value = true
  loadError.value = false
  try {
    const result = await PricingApi.getPriceRules({
      ...filters,
      stage: filters.stage || undefined,
      resolution: filters.resolution || undefined,
      status: filters.status || undefined
    })
    if (sequence === loadSequence) {
      list.value = result.list
      total.value = result.total
    }
  } catch {
    if (sequence === loadSequence) {
      list.value = []
      total.value = 0
      loadError.value = true
    }
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}
function refresh() {
  filters.pageNo = 1
  return load()
}
function openEditor(row?: GenerationPriceRule) {
  if (!canManage.value) return
  editingSource.value = row ?? null
  Object.assign(form, {
    stage: row?.stage || 'FLAT',
    resolution: row?.resolution || '2K',
    unitPointCost: row?.unitPointCost,
    minCount: row?.minCount || 1,
    maxCount: row?.maxCount || 4,
    effectiveAt: new Date().toISOString(),
    expiresAt: ''
  })
  editorOpen.value = true
}
async function save() {
  if (!canManage.value || saving.value) return
  if (
    !Number.isSafeInteger(form.unitPointCost) ||
    !form.unitPointCost ||
    form.unitPointCost < 1 ||
    form.unitPointCost > 1000000000
  ) {
    message.error('请填写有效的正整数设计点')
    return
  }
  if (
    ![form.minCount, form.maxCount].every((n) => Number.isInteger(n) && n >= 1 && n <= 4) ||
    form.maxCount < form.minCount
  ) {
    message.error('生成数量必须在 1–4 张之间，且最多张数不能小于最少张数')
    return
  }
  const effective = Date.parse(form.effectiveAt),
    expires = form.expiresAt ? Date.parse(form.expiresAt) : null
  if (
    !Number.isFinite(effective) ||
    (expires !== null && (!Number.isFinite(expires) || expires <= effective))
  ) {
    message.error('请填写有效时间，失效时间须晚于生效时间')
    return
  }
  saving.value = true
  try {
    await PricingApi.createPriceRule({
      ...form,
      stage: form.stage as CreateGenerationPrice['stage'],
      resolution: form.resolution as CreateGenerationPrice['resolution'],
      unitPointCost: form.unitPointCost,
      effectiveAt: new Date(effective).toISOString(),
      expiresAt: expires === null ? null : new Date(expires).toISOString()
    })
    editorOpen.value = false
    message.success('价格已保存')
    await refresh()
  } catch (e: any) {
    message.error(e?.msg || '价格保存失败，请重试')
  } finally {
    saving.value = false
  }
}
async function retire(row: GenerationPriceRule) {
  if (!canManage.value || saving.value || row.status !== 'ACTIVE') return
  // D2-4 停用原因必填（留审计），与预算/报价发布的留痕强度一致
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `停用${stageLabel(row.stage)} ${row.resolution} 的这条价格？停用后如无其他有效价格，将无法创建该档位任务。`,
      '停用出图价格',
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
    await PricingApi.retirePriceRule(row.ruleId, { reason })
    message.success('价格已停用')
    await load()
  } catch (e: any) {
    message.error(e?.msg || '停用失败，请重试；该价格在停用成功前仍在计费')
  } finally {
    saving.value = false
  }
}
async function remove(row: GenerationPriceRule) {
  if (!canManage.value || saving.value || row.status === 'ACTIVE') return
  try {
    await message.confirm(
      '删除该已停用价格？历史扣费金额已快照在点数流水，追溯不受影响；删除后不可恢复。'
    )
  } catch {
    return
  }
  saving.value = true
  try {
    const ok = await PricingApi.deletePriceRule(row.ruleId)
    if (!ok) {
      message.warning('仅已停用的价格可删除；生效中请先停用')
      return
    }
    message.success('价格已删除')
    await load()
  } catch (e: any) {
    message.error(e?.msg || '删除失败，请重试')
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
