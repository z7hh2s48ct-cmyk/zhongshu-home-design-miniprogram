<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">对外报价</h1>
        <div class="zs-page-subtitle"
          >预算测算值保持只读；每次调价形成独立草稿，经显式发布后业主才可见</div
        >
      </div>
      <div>
        <el-button :disabled="saving" @click="back">返回预算</el-button>
        <el-button :loading="loading" :disabled="saving" @click="load">刷新</el-button>
      </div>
    </div>
    <el-alert v-if="!canQuery" title="没有预算查询权限" type="warning" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="mb-16px" />
    <el-alert
      v-if="detail && quotes.length && !currentPublic"
      title="业主端当前看不到任何报价（草稿未发布或已全部撤回/作废）——如需业主可见，请发布一个报价版本。"
      type="error"
      :closable="false"
      class="mb-16px"
      show-icon
    />
    <template v-if="detail">
      <div class="zs-table-card mb-16px">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="项目"
            >{{ detail.projectName || '未命名' }} · {{ detail.projectId }}</el-descriptions-item
          >
          <el-descriptions-item label="预算修订"
            >{{ detail.revisionNo }} / 当前 {{ detail.currentVersion }}</el-descriptions-item
          >
          <el-descriptions-item label="完整性">{{
            detail.completeness === 'COMPLETE' ? '完整' : '待补'
          }}</el-descriptions-item>
          <el-descriptions-item label="系统测算总额"
            >{{ money(detail.totalCents) }} 元</el-descriptions-item
          >
          <el-descriptions-item label="地区">{{
            detail.regionName || detail.regionCode || '待补'
          }}</el-descriptions-item>
          <el-descriptions-item label="报价公开状态">{{
            currentPublic ? '已有公开报价 V' + currentPublic.quoteVersion : '当前无公开报价'
          }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="zs-table-card mb-16px">
        <div class="zs-panel-title">创建报价草稿</div>
        <el-alert
          v-if="detail.completeness !== 'COMPLETE'"
          title="预算存在待补项，须先完成预算修订，不能将已计价小计作为完整报价。"
          type="warning"
          :closable="false"
          class="mb-16px"
        />
        <el-alert
          v-else-if="!canQuote"
          title="没有报价草稿权限；仍可查看历史报价。"
          type="info"
          :closable="false"
          class="mb-16px"
        />
        <el-form label-width="130px" @submit.prevent="create">
          <el-form-item label="系统测算总额"
            ><strong>{{ money(detail.totalCents) }} 元（不可修改）</strong></el-form-item
          >
          <el-form-item label="定价方式">
            <el-radio-group v-model="form.mode" :disabled="!canCreate">
              <el-radio value="FINAL">填写最终报价</el-radio>
              <el-radio value="ADJUSTMENT">填写调价金额</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item
            :label="form.mode === 'FINAL' ? '最终报价（元）' : '调价金额（元）'"
            required
          >
            <el-input
              v-model="form.amount"
              :disabled="!canCreate"
              maxlength="14"
              placeholder="最多两位小数；调价可为负数"
            />
          </el-form-item>
          <el-form-item label="调价原因" required>
            <el-input
              v-model="form.reason"
              :disabled="!canCreate"
              type="textarea"
              maxlength="500"
              show-word-limit
            />
          </el-form-item>
          <el-alert
            v-if="formError"
            :title="formError"
            type="error"
            :closable="false"
            class="mb-16px"
          />
          <div class="quote-actions">
            <el-button type="primary" :loading="saving" :disabled="!canCreate" @click="create"
              >保存报价草稿</el-button
            >
          </div>
        </el-form>
      </div>

      <div class="zs-table-card mb-16px">
        <div class="zs-panel-title">报价版本</div>
        <el-table :data="quotes" stripe empty-text="暂无报价草稿或历史">
          <el-table-column label="版本" width="90"
            ><template #default="{ row }">V{{ row.quoteVersion }}</template></el-table-column
          >
          <el-table-column label="状态" width="135"
            ><template #default="{ row }">
              <el-tag :type="tagType(row)">{{ statusLabel(row) }}</el-tag>
            </template></el-table-column
          >
          <el-table-column label="预算修订" width="105"
            ><template #default="{ row }">修订 {{ row.revisionNo }}</template></el-table-column
          >
          <el-table-column label="系统测算（元）" min-width="140"
            ><template #default="{ row }">{{
              money(row.calculatedTotalCents)
            }}</template></el-table-column
          >
          <el-table-column label="调价（元）" min-width="125"
            ><template #default="{ row }">{{
              signedMoney(row.adjustmentCents)
            }}</template></el-table-column
          >
          <el-table-column label="最终报价（元）" min-width="140"
            ><template #default="{ row }"
              ><strong>{{ money(row.finalPriceCents) }}</strong></template
            ></el-table-column
          >
          <el-table-column prop="reason" label="内部调价原因" min-width="220" />
          <el-table-column label="发布时间" min-width="175"
            ><template #default="{ row }">{{
              displayTime(row.publishedAt)
            }}</template></el-table-column
          >
          <el-table-column label="操作" min-width="230" fixed="right"
            ><template #default="{ row }">
              <el-button
                link
                type="primary"
                :class="{ 'zs-preview-active': selected && selected.quoteId === row.quoteId }"
                @click="selected = row"
                >预览</el-button
              >
              <el-button
                v-if="row.status === 'DRAFT'"
                link
                type="success"
                :disabled="saving || row.stale || !canPublish"
                @click="openAction(row, 'PUBLISH')"
                >发布</el-button
              >
              <el-button
                v-if="row.currentPublic"
                link
                type="danger"
                :disabled="saving || !canPublish"
                @click="openAction(row, 'WITHDRAW')"
                >撤回</el-button
              >
              <el-button
                v-if="canPublish && row.status === 'DRAFT'"
                link
                type="danger"
                :disabled="saving"
                @click="discard(row)"
                >作废</el-button
              >
            </template></el-table-column
          >
        </el-table>
      </div>

      <div v-if="selected" class="zs-table-card">
        <div class="zs-panel-title">业主端同源预览 · 报价 V{{ selected.quoteVersion }}</div>
        <el-alert
          title="此预览直接使用业主端公开字段，不包含内部单价、数量、调价原因或备注。"
          type="info"
          :closable="false"
          class="mb-16px"
        />
        <el-descriptions :column="3" border>
          <el-descriptions-item label="项目">{{
            selected.preview.projectName || '未命名'
          }}</el-descriptions-item>
          <el-descriptions-item label="系统测算"
            >{{ money(selected.preview.calculatedTotalCents) }} 元</el-descriptions-item
          >
          <el-descriptions-item label="最终报价"
            ><strong>{{ money(selected.preview.finalPriceCents) }} 元</strong></el-descriptions-item
          >
          <el-descriptions-item label="主体"
            >{{ money(selected.preview.categoryTotals.BODY) }} 元</el-descriptions-item
          >
          <el-descriptions-item label="外装"
            >{{ money(selected.preview.categoryTotals.EXTERIOR) }} 元</el-descriptions-item
          >
          <el-descriptions-item label="状态">{{ statusLabel(selected) }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="selected.preview.items" size="small" class="mt-16px">
          <el-table-column prop="publicName" label="公开项目" min-width="190" />
          <el-table-column label="分类" width="90"
            ><template #default="{ row }">{{
              row.category === 'BODY' ? '主体' : '外装'
            }}</template></el-table-column
          >
          <el-table-column label="公开金额（元）" min-width="140"
            ><template #default="{ row }">{{ money(row.amountCents) }}</template></el-table-column
          >
        </el-table>
        <p class="zs-page-subtitle">{{ selected.preview.disclaimer }}</p>
      </div>
    </template>

    <el-dialog
      v-model="actionVisible"
      :title="action === 'PUBLISH' ? '发布报价' : '撤回报价'"
      width="min(560px, 94vw)"
      :close-on-click-modal="false"
    >
      <el-alert
        :title="
          action === 'PUBLISH'
            ? '发布后业主可见本版本；后续调整须新建报价版本。'
            : '撤回后业主端不再展示任何旧报价，不会自动回退。'
        "
        :type="action === 'PUBLISH' ? 'warning' : 'error'"
        :closable="false"
      />
      <el-input
        v-model="actionReason"
        type="textarea"
        maxlength="500"
        show-word-limit
        class="mt-16px"
        placeholder="操作原因（必填）"
      />
      <el-alert
        v-if="actionError"
        :title="actionError"
        type="error"
        :closable="false"
        class="mt-16px"
      />
      <template #footer>
        <el-button :disabled="saving" @click="actionVisible = false">取消</el-button>
        <el-button
          :type="action === 'PUBLISH' ? 'primary' : 'danger'"
          :loading="saving"
          @click="submitAction"
          >确认{{ action === 'PUBLISH' ? '发布' : '撤回' }}</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import * as RevisionApi from '@/api/zs/budget-revision'
import * as QuoteApi from '@/api/zs/budget-quote'
import type { BudgetDetail } from '@/api/zs/budget-revision'
import type { BudgetQuote } from '@/api/zs/budget-quote'
import { checkPermi } from '@/utils/permission'
import { useUserStore } from '@/store/modules/user'
import { getTenantId, getVisitTenantId } from '@/utils/auth'
import { displayTime, errorText, formatCents, validId } from './revision-form'
import { ElMessageBox } from 'element-plus'
import { quotePayload } from './quote-form'

defineOptions({ name: 'ZsBudgetQuote' })
const route = useRoute(),
  router = useRouter(),
  message = useMessage(),
  userStore = useUserStore()
const actor = () => JSON.stringify([userStore.getUser.id, getTenantId(), getVisitTenantId()])
const canQuery = computed(() => checkPermi(['design:budget:query']))
const canQuote = computed(() => canQuery.value && checkPermi(['design:budget:quote']))
const canPublish = computed(() => canQuery.value && checkPermi(['design:budget:quote-publish']))
const detail = ref<BudgetDetail | null>(null),
  quotes = ref<BudgetQuote[]>([]),
  selected = ref<BudgetQuote | null>(null)
const loading = ref(false),
  saving = ref(false),
  error = ref(''),
  formError = ref('')
const form = reactive<{ mode: 'FINAL' | 'ADJUSTMENT'; amount: string; reason: string }>({
  mode: 'FINAL',
  amount: '',
  reason: ''
})
const canCreate = computed(
  () =>
    canQuote.value &&
    detail.value?.completeness === 'COMPLETE' &&
    !detail.value.readOnly &&
    !saving.value
)
const currentPublic = computed(() => quotes.value.find((quote) => quote.currentPublic))
const money = (value: number | null | undefined) => (value == null ? '—' : formatCents(value))
const signedMoney = (value: number) =>
  (value > 0 ? '+' : value < 0 ? '−' : '') + formatCents(Math.abs(value))
const statusLabel = (quote: BudgetQuote) =>
  quote.stale
    ? '待复核草稿'
    : quote.currentPublic
      ? '已公开'
      : { DRAFT: '草稿', PUBLISHED: '历史已发布', WITHDRAWN: '已撤回', DISCARDED: '已作废' }[
          quote.status
        ]
const tagType = (quote: BudgetQuote) =>
  quote.currentPublic
    ? 'success'
    : quote.status === 'WITHDRAWN'
      ? 'danger'
      : quote.stale
        ? 'warning'
        : 'info'
let sequence = 0,
  loadedActor = '',
  pending: { fingerprint: string; key: string } | undefined
const keyFor = (operation: string, payload: unknown) => {
  const fingerprint = JSON.stringify([actor(), operation, payload])
  if (pending?.fingerprint !== fingerprint) pending = { fingerprint, key: crypto.randomUUID() }
  return pending.key
}
async function load() {
  const budgetId = route.params.budgetId
  error.value = ''
  if (!canQuery.value || !validId(budgetId)) {
    if (canQuery.value) error.value = '预算编号无效'
    return
  }
  const request = ++sequence,
    scope = actor()
  loading.value = true
  try {
    const [budget, history] = await Promise.all([
      RevisionApi.getEstimate(budgetId),
      QuoteApi.getQuotes(budgetId)
    ])
    if (request !== sequence || scope !== actor() || !canQuery.value) return
    if (budget.budgetId !== budgetId || history.some((quote) => quote.budgetId !== budgetId))
      throw Error('报价响应上下文不匹配')
    detail.value = budget
    quotes.value = history
    selected.value = history.find((quote) => quote.currentPublic) || history[0] || null
    loadedActor = scope
    pending = undefined
  } catch (cause) {
    if (request === sequence) error.value = errorText(cause)
  } finally {
    if (request === sequence) loading.value = false
  }
}
async function create() {
  if (!canCreate.value || !detail.value || loadedActor !== actor()) return
  formError.value = ''
  let payload
  try {
    payload = quotePayload(detail.value, form.mode, form.amount, form.reason)
  } catch (cause) {
    formError.value = errorText(cause)
    return
  }
  saving.value = true
  try {
    const result = await QuoteApi.createQuote(
      detail.value.budgetId,
      payload,
      keyFor('create', payload)
    )
    if (loadedActor !== actor() || result.budgetId !== detail.value.budgetId)
      throw Error('报价响应上下文无效')
    message.success('报价草稿已保存，尚未对业主发布')
    Object.assign(form, { amount: '', reason: '' })
    await load()
  } catch (cause) {
    formError.value = errorText(cause) + '；输入已保留，可按原幂等键重试。'
  } finally {
    saving.value = false
  }
}
const actionVisible = ref(false),
  action = ref<'PUBLISH' | 'WITHDRAW'>('PUBLISH'),
  actionTarget = ref<BudgetQuote | null>(null),
  actionReason = ref(''),
  actionError = ref('')
function openAction(quote: BudgetQuote, next: 'PUBLISH' | 'WITHDRAW') {
  if (
    !canPublish.value ||
    saving.value ||
    (next === 'PUBLISH' && quote.stale) ||
    (next === 'WITHDRAW' && !quote.currentPublic)
  )
    return
  actionTarget.value = quote
  action.value = next
  // E-3 原因一次输入：发布默认带入草稿创建时填写的调价原因，可改
  actionReason.value = next === 'PUBLISH' ? quote.reason || '' : ''
  actionError.value = ''
  actionVisible.value = true
}
// E-3 作废草稿：治理只增不减的草稿堆积；业主端永不展示作废件
async function discard(quote: BudgetQuote) {
  if (!canPublish.value || saving.value || quote.status !== 'DRAFT') return
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(
      '作废报价 V' + quote.quoteVersion + ' 草稿？作废后不可恢复，业主端永不展示。原因将写入审计。',
      '作废报价草稿',
      {
        type: 'warning',
        confirmButtonText: '确认作废',
        cancelButtonText: '取消',
        inputPlaceholder: '作废原因（必填）',
        inputValidator: (v: string) => (v?.trim() ? true : '作废原因必填')
      }
    )
    reason = value.trim()
  } catch {
    return
  }
  saving.value = true
  try {
    await QuoteApi.discardQuote(
      quote.quoteId,
      { expectedVersion: quote.version, reason },
      'discard-' + quote.quoteId + '-' + quote.version
    )
    message.success('报价草稿已作废')
    await load()
  } catch (e: any) {
    message.error(e?.msg || '作废失败，请刷新后重试')
  } finally {
    saving.value = false
  }
}
async function submitAction() {
  const target = actionTarget.value,
    reason = actionReason.value
  if (!target || !canPublish.value || saving.value) return
  if (!reason || reason !== reason.trim() || reason.length > 500) {
    actionError.value = '请填写500字以内且首尾无空格的操作原因'
    return
  }
  const payload = { expectedVersion: target.version, reason }
  saving.value = true
  actionError.value = ''
  try {
    const result =
      action.value === 'PUBLISH'
        ? await QuoteApi.publishQuote(
            target.quoteId,
            payload,
            keyFor('publish/' + target.quoteId, payload)
          )
        : await QuoteApi.withdrawQuote(
            target.quoteId,
            payload,
            keyFor('withdraw/' + target.quoteId, payload)
          )
    if (result.quoteId !== target.quoteId || loadedActor !== actor())
      throw Error('报价状态响应无效')
    message.success(
      action.value === 'PUBLISH' ? '报价已发布给业主' : '报价已撤回，旧报价不会自动恢复公开'
    )
    actionVisible.value = false
    await load()
  } catch (cause) {
    actionError.value = errorText(cause) + '；若版本已变化请刷新后核对。'
  } finally {
    saving.value = false
  }
}
function back() {
  void router.push('/zs/budget-estimates/' + route.params.budgetId)
}
watch(
  () => [route.params.budgetId, userStore.getUser.id, canQuery.value],
  () => {
    void load()
  }
)
onBeforeUnmount(() => {
  sequence++
})
onMounted(load)
</script>

<style scoped lang="scss">
.quote-actions {
  display: flex;
  justify-content: flex-end;
}
</style>

<style lang="scss" scoped>
.zs-preview-active {
  font-weight: 700;
  text-decoration: underline;
}
</style>
