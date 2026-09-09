<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">预算配置</h1>
        <div class="zs-page-subtitle">地区价、标准选项与自定义模板；历史预算按快照保留</div>
      </div>
      <div class="flex flex-wrap gap-8px">
        <router-link v-if="canQuery" to="/zs/budget-estimates">
          <el-button>项目预算与修订</el-button>
        </router-link>
        <el-button
          v-if="canConfigure"
          class="zs-btn-primary"
          :disabled="loading"
          @click="openEditor()"
        >
          <Icon icon="ep:plus" class="mr-4px" />{{ createLabel }}
        </el-button>
      </div>
    </div>
    <el-alert
      v-if="!canQuery"
      title="没有预算查询权限，请联系管理员授权"
      type="warning"
      :closable="false"
    />
    <div v-else class="zs-table-card">
      <el-tabs v-model="kind">
        <el-tab-pane label="建造地区" name="regions" />
        <el-tab-pane label="预算项 / 自定义模板" name="items" />
        <el-tab-pane label="计价选项" name="options" />
        <el-tab-pane label="地区价格" name="prices" />
      </el-tabs>
      <el-alert
        :title="
          kind === 'prices'
            ? '保存只形成草稿；发布后才用于新预算。空单价是待补，0元必须说明免费原因。'
            : '标准3+7项身份固定；自定义模板默认仅后台使用，公开选择需要明确开启并配置有效价格。'
        "
        type="info"
        :closable="false"
        class="mb-16px"
      />
      <div class="budget-filters">
        <el-select
          v-if="kind === 'options' || kind === 'prices'"
          v-model="itemFilter"
          clearable
          filterable
          placeholder="筛选预算项"
          class="budget-select"
        >
          <el-option
            v-for="item in choices.items"
            :key="item.itemId"
            :value="item.itemId!"
            :label="`${item.name} · ${item.code}`"
          />
        </el-select>
        <el-select
          v-if="kind === 'prices'"
          v-model="regionFilter"
          filterable
          placeholder="选择建造地区"
          class="budget-select"
        >
          <el-option
            v-for="region in choices.regions"
            :key="region.regionId"
            :value="region.code!"
            :label="region.name"
          />
        </el-select>
        <el-select
          v-if="kind === 'prices'"
          v-model="optionFilter"
          clearable
          filterable
          placeholder="筛选计价选项"
          class="budget-select"
        >
          <el-option
            v-for="option in filteredOptions"
            :key="option.optionId"
            :value="option.optionId!"
            :label="optionName(option)"
          />
        </el-select>
        <el-button :loading="loading" @click="refresh">刷新</el-button>
      </div>
      <el-alert
        v-if="loadError"
        :title="loadError"
        type="error"
        :closable="false"
        class="mb-16px"
      />
      <el-table
        :data="rows"
        v-loading="loading"
        stripe
        :empty-text="kind === 'prices' && !regionFilter ? '请先选择建造地区' : '暂无配置'"
      >
        <el-table-column v-if="kind !== 'prices'" label="名称 / 编码" min-width="230">
          <template #default="{ row }"
            ><div>{{ row.name || row.label }}</div
            ><small>{{ row.code }}</small></template
          >
        </el-table-column>
        <el-table-column v-if="kind === 'items'" label="分类 / 来源" min-width="150">
          <template #default="{ row }"
            >{{ row.category === 'BODY' ? '主体' : '外装' }} ·
            {{ row.source === 'STANDARD' ? '标准项' : '自定义模板' }}</template
          >
        </el-table-column>
        <el-table-column v-if="kind === 'items'" label="小程序可选" width="110">
          <template #default="{ row }">{{ row.publicSelectable ? '已开放' : '仅后台' }}</template>
        </el-table-column>
        <el-table-column v-if="kind === 'options'" label="预算项 / 选择组" min-width="180">
          <template #default="{ row }"
            >{{ itemName(row.itemId) }} / {{ row.selectionGroup }}</template
          >
        </el-table-column>
        <el-table-column v-if="kind === 'options'" label="数量来源 / 单位" min-width="200">
          <template #default="{ row }"
            >{{ quantitySources.find((s) => s.value === row.quantitySource)?.label }} /
            {{ units.find((u) => u.value === row.unit)?.label }}</template
          >
        </el-table-column>
        <el-table-column v-if="kind === 'prices'" label="地区 / 计价选项" min-width="230">
          <template #default="{ row }"
            >{{ row.regionCode }} / {{ optionNameById(row.optionId) }}</template
          >
        </el-table-column>
        <el-table-column v-if="kind === 'prices'" label="单价（元）" min-width="120">
          <template #default="{ row }"
            >{{ formatCents(row.unitPriceCents)
            }}<el-tag v-if="row.unitPriceCents === 0" size="small">免费</el-tag></template
          >
        </el-table-column>
        <el-table-column v-if="kind === 'prices'" label="有效期（左闭右开）" min-width="230">
          <template #default="{ row }"
            ><div>{{ displayTime(row.effectiveAt) }}</div
            ><small
              >至 {{ row.expiresAt ? displayTime(row.expiresAt) : '长期有效' }}</small
            ></template
          >
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }"
            ><el-tag
              :type="
                kind === 'prices'
                  ? row.status === 'PUBLISHED'
                    ? 'success'
                    : 'info'
                  : row.enabled
                    ? 'success'
                    : 'info'
              "
              >{{
                kind === 'prices' ? priceStatus[row.status] : row.enabled ? '启用' : '停用'
              }}</el-tag
            ></template
          >
        </el-table-column>
        <el-table-column label="版本" prop="version" width="75" />
        <el-table-column label="操作" min-width="210" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="canConfigure && (kind !== 'prices' || row.status === 'DRAFT')"
              link
              type="primary"
              @click="openEditor(row)"
              >编辑</el-button
            >
            <el-button v-if="kind === 'items'" link type="primary" @click="showOptions(row)"
              >管理选项</el-button
            >
            <el-button v-if="kind === 'options'" link type="primary" @click="showPrices(row)"
              >地区价</el-button
            >
            <el-button
              v-if="kind === 'prices' && canPublish && row.status === 'DRAFT'"
              link
              type="primary"
              @click="openTransition(row, 'publish')"
              >发布</el-button
            >
            <el-button
              v-if="kind === 'prices' && canPublish && row.status === 'PUBLISHED'"
              link
              type="danger"
              @click="openTransition(row, 'disable')"
              >停用</el-button
            >
            <span v-if="kind === 'prices' && row.status !== 'DRAFT'" class="zs-page-subtitle"
              >金额只读</span
            >
          </template>
        </el-table-column>
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

    <el-dialog
      v-model="editorVisible"
      :title="`${editing ? '编辑' : '新增'}${labels[editorKind]}`"
      width="min(680px, 94vw)"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
    >
      <el-form label-width="126px" @submit.prevent="save">
        <template v-if="editorKind !== 'prices'">
          <el-form-item label="稳定编码" required
            ><el-input
              v-model="form.code"
              :disabled="!!editing || saving"
              :maxlength="editorKind === 'regions' ? 32 : 64"
              placeholder="创建后不可修改"
          /></el-form-item>
          <el-form-item :label="editorKind === 'options' ? '选项名称' : '名称'" required
            ><el-input
              v-if="editorKind === 'options'"
              v-model="form.label"
              :disabled="saving"
              maxlength="100" /><el-input
              v-else
              v-model="form.name"
              :disabled="saving"
              maxlength="100"
          /></el-form-item>
        </template>
        <template v-if="editorKind === 'items'">
          <el-form-item label="分类" required
            ><el-radio-group v-model="form.category" :disabled="!!editing || saving"
              ><el-radio value="BODY">主体</el-radio
              ><el-radio value="EXTERIOR">外装</el-radio></el-radio-group
            ></el-form-item
          >
          <el-form-item label="小程序可选"
            ><el-switch v-model="form.publicSelectable" :disabled="saving" /><span class="ml-8px"
              >先配选项、有效地区价，再显式开放</span
            ></el-form-item
          >
        </template>
        <template v-if="editorKind === 'options'">
          <el-form-item label="所属预算项" required
            ><el-select v-model="form.itemId" filterable :disabled="!!editing || saving"
              ><el-option
                v-for="item in choices.items"
                :key="item.itemId"
                :value="item.itemId!"
                :label="`${item.name} · ${item.code}`" /></el-select
          ></el-form-item>
          <el-form-item label="选择组" required
            ><el-input
              v-model="form.selectionGroup"
              :disabled="saving"
              maxlength="64"
              placeholder="如 DOOR；同组互斥，不同组可组合"
          /></el-form-item>
          <el-form-item label="数量来源" required
            ><el-select v-model="form.quantitySource" :disabled="saving" @change="alignUnit"
              ><el-option
                v-for="source in quantitySources"
                :key="source.value"
                :value="source.value"
                :label="source.label" /></el-select
          ></el-form-item>
          <el-form-item
            v-if="form.quantitySource === 'PROJECT_QUANTITY'"
            label="核定工程量"
            required
            ><el-select v-model="form.quantityKey" :disabled="saving" @change="alignUnit"
              ><el-option
                v-for="key in quantityKeys"
                :key="key.value"
                :value="key.value"
                :label="key.label" /></el-select
          ></el-form-item>
          <el-form-item label="计价单位" required
            ><el-select v-model="form.unit" :disabled="saving"
              ><el-option
                v-for="unit in units"
                :key="unit.value"
                :value="unit.value"
                :label="unit.label" /></el-select
          ></el-form-item>
          <el-form-item label="计价依据"
            ><el-input
              v-model="form.sourceReference"
              type="textarea"
              :disabled="saving"
              maxlength="500"
              show-word-limit
              placeholder="记录培训表单元格或已核定的数量依据"
          /></el-form-item>
        </template>
        <template v-if="editorKind === 'prices'">
          <el-form-item label="建造地区" required
            ><el-select v-model="form.regionCode" filterable :disabled="!!editing || saving"
              ><el-option
                v-for="region in choices.regions"
                :key="region.regionId"
                :value="region.code!"
                :label="region.name" /></el-select
          ></el-form-item>
          <el-form-item label="计价选项" required
            ><el-select v-model="form.optionId" filterable :disabled="!!editing || saving"
              ><el-option
                v-for="option in choices.options"
                :key="option.optionId"
                :value="option.optionId!"
                :label="optionName(option)" /></el-select
          ></el-form-item>
          <el-form-item label="单价（元）"
            ><el-input
              v-model="form.priceYuan"
              :disabled="saving"
              placeholder="留空 = 待补价；0 = 明确免费"
              maxlength="10"
          /></el-form-item>
          <el-form-item
            label="免费原因"
            :required="form.priceYuan !== '' && Number(form.priceYuan) === 0"
            ><el-input
              v-model="form.freeReason"
              :disabled="saving"
              type="textarea"
              maxlength="500"
              show-word-limit
          /></el-form-item>
          <el-form-item label="生效时间"
            ><el-date-picker
              v-model="form.effectiveAt"
              :disabled="saving"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ssZ"
              placeholder="发布前必须填写"
          /></el-form-item>
          <el-form-item label="失效时间"
            ><el-date-picker
              v-model="form.expiresAt"
              :disabled="saving"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ssZ"
              placeholder="留空长期有效，失效时刻不再计价"
          /></el-form-item>
        </template>
        <el-form-item v-if="editorKind !== 'prices'" label="启用"
          ><el-switch v-model="form.enabled" :disabled="saving"
        /></el-form-item>
        <el-form-item v-if="editorKind === 'items' || editorKind === 'options'" label="排序"
          ><el-input-number
            v-model="form.sortOrder"
            :disabled="saving"
            :min="0"
            :max="100000"
            :precision="0"
        /></el-form-item>
      </el-form>
      <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" />
      <template #footer
        ><el-button :disabled="saving" @click="editorVisible = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="save">{{
          editorKind === 'prices' ? '保存草稿' : '保存配置'
        }}</el-button></template
      >
    </el-dialog>

    <el-dialog
      v-model="transitionVisible"
      :title="transitionAction === 'publish' ? '确认发布地区价格' : '确认停用地区价格'"
      width="min(520px, 94vw)"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
    >
      <p
        >{{ transitionRow?.regionCode }} · {{ optionNameById(transitionRow?.optionId) }} ·
        {{ formatCents(transitionRow?.unitPriceCents) }}元</p
      >
      <el-alert
        title="仅影响有效期内的新预算，既有预算及报价快照不变。"
        type="info"
        :closable="false"
      />
      <el-input
        v-model="transitionReason"
        :disabled="saving"
        class="mt-16px"
        type="textarea"
        maxlength="500"
        show-word-limit
        placeholder="填写发布 / 停用原因（必填）"
      />
      <el-alert
        v-if="saveError"
        :title="saveError"
        type="error"
        :closable="false"
        class="mt-16px"
      />
      <template #footer
        ><el-button :disabled="saving" @click="transitionVisible = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="applyTransition">{{
          transitionAction === 'publish' ? '确认发布' : '确认停用'
        }}</el-button></template
      >
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import * as BudgetApi from '@/api/zs/budget'
import type { CatalogKind, CatalogRow } from '@/api/zs/budget'
import { checkPermi } from '@/utils/permission'
import {
  createForm,
  formPayload,
  formatCents,
  quantitySources,
  quantityKeys,
  rowId,
  units
} from './catalog-form'

defineOptions({ name: 'ZsBudgetCatalog' })
const message = useMessage()
const canQuery = computed(() => checkPermi(['design:budget:query']))
const canConfigure = computed(() => canQuery.value && checkPermi(['design:budget:configure']))
const canPublish = computed(() => canQuery.value && checkPermi(['design:budget:price-publish']))
const labels = {
  regions: '建造地区',
  items: '自定义预算项',
  options: '计价选项',
  prices: '地区价格草稿'
}
const priceStatus = { DRAFT: '草稿', PUBLISHED: '已发布', DISABLED: '已停用' }
const kind = ref<CatalogKind>('regions')
const rows = ref<CatalogRow[]>([])
const total = ref(0),
  pageNo = ref(1),
  loading = ref(false),
  saving = ref(false)
const itemFilter = ref(''),
  regionFilter = ref(''),
  optionFilter = ref('')
const loadError = ref(''),
  saveError = ref('')
const choices = reactive({
  regions: [] as CatalogRow[],
  items: [] as CatalogRow[],
  options: [] as CatalogRow[]
})
const filteredOptions = computed(() =>
  choices.options.filter((o) => !itemFilter.value || o.itemId === itemFilter.value)
)
const createLabel = computed(() => `新增${labels[kind.value]}`)
const itemName = (id?: string) => choices.items.find((i) => i.itemId === id)?.name || id || '未指定'
const optionName = (row: CatalogRow) => `${itemName(row.itemId)} / ${row.label}`
const optionNameById = (id?: string) => {
  const option = choices.options.find((o) => o.optionId === id)
  return option ? optionName(option) : id || '未指定'
}
const displayTime = (value?: string) =>
  value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '待填写'
const errorText = (error: any) =>
  error?.msg || error?.message || '请求失败，请重试；若版本冲突请刷新后重新核对'
let readSequence = 0

async function load() {
  const sequence = ++readSequence
  if (!canQuery.value) return
  rows.value = []
  total.value = 0
  loadError.value = ''
  if (kind.value === 'prices' && !regionFilter.value) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const result = await BudgetApi.getCatalogPage(kind.value, {
      pageNo: pageNo.value,
      pageSize: 20,
      ...(kind.value === 'options' && itemFilter.value ? { itemId: itemFilter.value } : {}),
      ...(kind.value === 'prices'
        ? {
            regionCode: regionFilter.value,
            ...(optionFilter.value ? { optionId: optionFilter.value } : {})
          }
        : {})
    })
    if (sequence === readSequence) {
      rows.value = result.list
      total.value = result.total
    }
  } catch (error) {
    if (sequence === readSequence) loadError.value = errorText(error)
  } finally {
    if (sequence === readSequence) loading.value = false
  }
}
async function loadChoices() {
  if (!canQuery.value) return
  await Promise.all(
    (['regions', 'items', 'options'] as const).map(async (resource) => {
      const all: CatalogRow[] = []
      for (let page = 1; ; page++) {
        const result = await BudgetApi.getCatalogPage(resource, { pageNo: page, pageSize: 100 })
        all.push(...result.list)
        if (all.length >= result.total || !result.list.length) break
      }
      choices[resource] = all
    })
  )
}
async function refresh() {
  try {
    await loadChoices()
  } catch (error) {
    loadError.value = errorText(error)
    return
  }
  await load()
}
watch([kind, itemFilter, regionFilter, optionFilter], () => {
  pageNo.value = 1
  void load()
})
function showOptions(row: CatalogRow) {
  itemFilter.value = row.itemId!
  kind.value = 'options'
}
function showPrices(row: CatalogRow) {
  itemFilter.value = row.itemId!
  optionFilter.value = row.optionId!
  kind.value = 'prices'
}

const editorVisible = ref(false),
  editorKind = ref<CatalogKind>('regions')
const editing = ref<CatalogRow>()
const form = reactive(createForm())
let pendingCommand: { fingerprint: string; key: string } | undefined
function commandKey(action: string, data: unknown) {
  const fingerprint = JSON.stringify([action, data])
  if (pendingCommand?.fingerprint !== fingerprint)
    pendingCommand = { fingerprint, key: crypto.randomUUID() }
  return pendingCommand.key
}
function openEditor(row?: CatalogRow) {
  if (
    !canConfigure.value ||
    saving.value ||
    (kind.value === 'prices' && row && row.status !== 'DRAFT')
  )
    return
  editorKind.value = kind.value
  editing.value = row ? { ...row } : undefined
  Object.assign(form, createForm(row))
  if (!row) {
    form.itemId = itemFilter.value
    form.regionCode = regionFilter.value
    form.optionId = optionFilter.value
  }
  saveError.value = ''
  editorVisible.value = true
}
function alignUnit() {
  const unit =
    form.quantitySource === 'PROJECT_QUANTITY'
      ? quantityKeys.find((k) => k.value === form.quantityKey)?.unit
      : quantitySources.find((s) => s.value === form.quantitySource)?.unit
  if (unit) form.unit = unit
  else if (form.quantitySource === 'FIXED_ONE') form.unit = 'ITEM'
}
async function save() {
  if (saving.value || !canConfigure.value) return
  saveError.value = ''
  try {
    const payload = formPayload(editorKind.value, form, editing.value)
    const id = editing.value ? rowId(editorKind.value, editing.value) : undefined
    saving.value = true
    await BudgetApi.saveCatalog(
      editorKind.value,
      id,
      payload,
      commandKey(`${editorKind.value}/${id || 'new'}`, payload)
    )
    pendingCommand = undefined
    editorVisible.value = false
    message.success(editorKind.value === 'prices' ? '草稿已保存，尚未发布' : '配置已保存')
    await refresh()
  } catch (error) {
    saveError.value = errorText(error)
  } finally {
    saving.value = false
  }
}
const transitionVisible = ref(false),
  transitionRow = ref<CatalogRow>()
const transitionAction = ref<'publish' | 'disable'>('publish'),
  transitionReason = ref('')
function openTransition(row: CatalogRow, action: 'publish' | 'disable') {
  if (!canPublish.value || saving.value) return
  transitionRow.value = { ...row }
  transitionAction.value = action
  transitionReason.value = ''
  saveError.value = ''
  transitionVisible.value = true
}
async function applyTransition() {
  if (saving.value || !canPublish.value || !transitionRow.value?.priceId) return
  if (!transitionReason.value.trim()) {
    saveError.value = '请填写操作原因'
    return
  }
  const row = transitionRow.value
  saving.value = true
  saveError.value = ''
  try {
    const payload = { expectedVersion: row.version, reason: transitionReason.value.trim() }
    await BudgetApi.transitionPrice(
      row.priceId!,
      transitionAction.value,
      payload,
      commandKey(`${row.priceId}/${transitionAction.value}`, payload)
    )
    pendingCommand = undefined
    transitionVisible.value = false
    message.success('价格状态已更新')
    await refresh()
  } catch (error) {
    saveError.value = errorText(error)
  } finally {
    saving.value = false
  }
}
onMounted(refresh)
</script>

<style scoped lang="scss">
.budget-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}

.budget-select {
  width: 240px;
}
</style>
