<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">授权码管理</h1>
        <div class="zs-page-subtitle">通过授权码控制小程序用户准入与微信账号绑定</div>
      </div>
      <div class="zs-actions">
        <el-button class="zs-btn-primary" @click="single.visible = true">
          <Icon icon="ep:plus" class="mr-4px" /> 生成授权码
        </el-button>
        <el-button @click="$router.push('/zs/access-code/batch')">批量生成授权码</el-button>
      </div>
    </div>

    <!-- 统计卡 -->
    <div class="zs-stat-cards">
      <div class="zs-stat-card" v-for="s in stats" :key="s.label">
        <div class="zs-stat-icon" :style="{ color: s.color }"><Icon :icon="s.icon" /></div>
        <div>
          <div class="zs-stat-label">{{ s.label }}</div>
          <div class="zs-stat-value">{{ s.value }}</div>
        </div>
      </div>
    </div>

    <div class="zs-table-card">
      <el-tabs v-model="activeTab" @tab-change="search">
        <el-tab-pane label="全部" name="ALL" />
        <el-tab-pane label="未使用" name="ACTIVE" />
        <el-tab-pane label="已绑定" name="CONSUMED" />
        <el-tab-pane label="已停用" name="DISABLED" />
      </el-tabs>

      <el-alert
        v-if="loadError"
        type="error"
        :closable="false"
        title="授权码列表加载失败，请重试后再执行批量操作"
        style="margin-bottom: 12px"
      />

      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column label="授权码" prop="codeMask" width="180" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="zs-tag" :class="statusColor(row.status)">{{
              statusText(row.status)
            }}</span>
          </template>
        </el-table-column>
        <el-table-column label="绑定用户" width="140">
          <template #default="{ row }">
            <span v-if="row.boundUser" class="zs-link" @click="openDetail(row)">{{
              maskUser(row.boundUser)
            }}</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="使用次数" width="90">
          <template #default="{ row }">{{ row.status === 'CONSUMED' ? '1/1' : '0/1' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.issuedAt) }}</template>
        </el-table-column>
        <el-table-column label="有效期至" width="150">
          <template #default="{ row }">{{
            row.expiresAt ? fmtDate(row.expiresAt) : '长期'
          }}</template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <span class="zs-link" @click="openDetail(row)">详情</span>
            <span v-if="row.status === 'ACTIVE'" class="zs-link" @click="doDisable(row)">停用</span>
            <span
              v-if="row.canCopy"
              v-hasPermi="['identity:access-code:export']"
              class="zs-link"
              @click="copyCode(row)"
              >复制授权码</span
            >
            <span v-if="row.status !== 'CONSUMED'" class="zs-link-danger" @click="doDelete(row)"
              >删除</span
            >
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

      <div class="zs-footnote">
        <div>明确规则：一码一账号，授权码仅可绑定一个微信账号；状态与绑定用户信息以表格为准。</div>
        <div>
          已停用的授权码不可重新启用（安全规则），请生成新码；已兑换码不可删除，解绑只会撤销用户访问、码仍作废。
        </div>
        <div>安全说明：无公开注册入口，授权码与绑定信息请妥善保管，禁止泄露与转售。</div>
      </div>
    </div>

    <!-- 单个生成授权码 -->
    <el-dialog
      v-model="single.visible"
      title="生成授权码"
      width="460px"
      :close-on-click-modal="false"
    >
      <el-form label-width="90px">
        <el-form-item label="有效期(天)">
          <el-input-number v-model="single.validityDays" :min="1" :max="3650" />
          <span class="ml-8px" style="font-size: 12px; color: #8a8a8a">留空为长期有效</span>
        </el-form-item>
        <el-form-item label="用途备注">
          <el-input
            v-model="single.purposeNote"
            placeholder="如：核心客户 / 渠道A"
            maxlength="100"
            style="width: 260px"
          />
        </el-form-item>
      </el-form>
      <div v-if="single.code" class="zs-single-code">
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="请立即复制保存；明文仅此一次展示。"
        />
        <code>{{ single.code }}</code>
      </div>
      <template #footer>
        <el-button :disabled="single.creating" @click="single.visible = false">关闭</el-button>
        <el-button
          v-if="!single.code"
          class="zs-btn-primary"
          :loading="single.creating"
          @click="createSingle"
          >生成</el-button
        >
        <el-button v-else class="zs-btn-primary" @click="copySingle">复制授权码</el-button>
      </template>
    </el-dialog>

    <!-- 授权码详情抽屉 -->
    <el-drawer v-model="detail.visible" title="授权码详情" size="520px">
      <div v-loading="detail.loading">
        <el-alert v-if="detail.error" type="error" :title="detail.error" :closable="false"
          ><el-button @click="loadDetail">重新加载</el-button></el-alert
        >
        <template v-if="detail.row">
          <h4 class="zs-drawer-section">基本信息</h4>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="授权码">{{ detail.row.codeMask }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{
              statusText(detail.row.status)
            }}</el-descriptions-item>
            <el-descriptions-item label="批次编号">{{
              detail.row.batchId || '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="有效期至">{{
              detail.row.expiresAt ? fmtDate(detail.row.expiresAt) : '长期'
            }}</el-descriptions-item>
            <el-descriptions-item label="发行时间">{{
              fmtTime(detail.row.issuedAt)
            }}</el-descriptions-item>
            <el-descriptions-item label="兑换时间">{{
              detail.row.consumedAt ? fmtTime(detail.row.consumedAt) : '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="明文交付时间" :span="2">{{
              detail.row.secretExposedAt ? fmtTime(detail.row.secretExposedAt) : '未交付明文'
            }}</el-descriptions-item>
          </el-descriptions>

          <template v-if="detail.account">
            <h4 class="zs-drawer-section">绑定用户</h4>
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="用户编号">{{ detail.account.id }}</el-descriptions-item>
              <el-descriptions-item label="昵称">{{
                detail.account.nickname || '—'
              }}</el-descriptions-item>
              <el-descriptions-item label="授权状态">{{ accountGrantText }}</el-descriptions-item>
              <el-descriptions-item label="可用点数">{{
                detail.account.availablePoints ?? '—'
              }}</el-descriptions-item>
              <el-descriptions-item label="项目 / 投稿 / 任务" :span="2"
                >{{ detail.account.stats?.projectCount ?? 0 }} /
                {{ detail.account.stats?.approvedSubmissionCount ?? 0 }} /
                {{ detail.account.stats?.aiJobCount ?? 0 }}</el-descriptions-item
              >
            </el-descriptions>
          </template>

          <template v-if="detail.orders?.length">
            <h4 class="zs-drawer-section">该用户近期充值记录</h4>
            <el-table :data="detail.orders" size="small" stripe>
              <el-table-column label="金额(元)" width="80">
                <template #default="{ row }">{{ (row.amountCents / 100).toFixed(2) }}</template>
              </el-table-column>
              <el-table-column label="点数" width="80">
                <template #default="{ row }">{{ row.basePoints + row.bonusPoints }}</template>
              </el-table-column>
              <el-table-column label="支付/到账" min-width="120">
                <template #default="{ row }"
                  >{{ payStateText(row.paymentState) }}/{{
                    payStateText(row.fulfillmentState)
                  }}</template
                >
              </el-table-column>
              <el-table-column label="时间" min-width="150">
                <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
              </el-table-column>
            </el-table>
          </template>

          <template v-if="detail.account?.recentLedger?.length">
            <h4 class="zs-drawer-section">该用户近期点数流水（含充值入账）</h4>
            <el-table :data="detail.account.recentLedger" size="small" stripe>
              <el-table-column label="类型" min-width="110">
                <template #default="{ row }">{{ ledgerTypeText(row.type) }}</template>
              </el-table-column>
              <el-table-column label="变动" width="80">
                <template #default="{ row }">
                  <span
                    :style="{
                      color: (row.delta || 0) > 0 ? '#3f9e56' : '#d0342c',
                      fontWeight: 600
                    }"
                  >
                    {{ (row.delta || 0) > 0 ? '+' : '' }}{{ row.delta }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="时间" width="150">
                <template #default="{ row }">{{
                  fmtTime(row.create_time || row.createdAt)
                }}</template>
              </el-table-column>
            </el-table>
          </template>

          <h4 class="zs-drawer-section">操作</h4>
          <div class="zs-drawer-actions">
            <el-button
              v-if="detail.row.canCopy"
              v-hasPermi="['identity:access-code:export']"
              @click="copyCode(detail.row)"
              >复制授权码</el-button
            >
            <el-button
              v-if="detail.row.status === 'ACTIVE'"
              type="warning"
              plain
              @click="disableAndRefresh(detail.row)"
              >停用</el-button
            >
            <el-button v-if="canUnbind" type="danger" plain @click="doUnbind">解绑用户</el-button>
            <el-button
              v-if="detail.row.status !== 'CONSUMED'"
              type="danger"
              plain
              @click="doDelete(detail.row)"
              >删除</el-button
            >
          </div>
          <p class="zs-drawer-hint"
            >解绑将立即撤销该用户的访问授权；授权码保持已兑换状态、不会恢复为未使用，如需重新激活请生成新码。</p
          >
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { fmtDate, fmtTime } from '@/utils/zsFormat'
import { ElMessageBox } from 'element-plus'

defineOptions({ name: 'ZsAccessCode' })
const message = useMessage()

const activeTab = ref('ALL')
const loading = ref(false)
/** 接口失败标记：用于把“加载失败”与“确实没有数据”区分开 */
const loadError = ref(false)
const list = ref<any[]>([])
const total = ref(0)
// 掩码搜索入口已暂时移除：后端 AccessCodeAdminController 声明了 codeMask 但未实现过滤（P3B 与查询索引一起补），
// 输入不生效属误导性 UI；待后端过滤落地后再恢复搜索框。
const query = reactive({ status: '', pageNo: 1, pageSize: 10 })

const stats = ref([
  { label: '未使用', value: 0, icon: 'ep:ticket', color: '#2d68c4' },
  { label: '已绑定', value: 0, icon: 'ep:user', color: '#3f9e56' },
  { label: '已过期', value: 0, icon: 'ep:clock', color: '#8a8a8a' },
  { label: '已停用', value: 0, icon: 'ep:circle-close', color: '#d0342c' }
])

const statusText = (s: string) =>
  ({ ACTIVE: '未使用', CONSUMED: '已绑定', DISABLED: '已停用' })[s] || s
const statusColor = (s: string) =>
  ({ ACTIVE: 'blue', CONSUMED: 'green', DISABLED: 'red' })[s] || 'gray'
const maskUser = (id: string) => (String(id).length > 10 ? `${String(id).slice(0, 6)}…` : id)
const ledgerTypeText = (t: string) =>
  ({
    RECHARGE_BASE_CREDIT: '充值基础点',
    RECHARGE_BONUS_CREDIT: '充值赠送点',
    FLAT_GENERATION_DEBIT: '平面生成扣点',
    ELEVATION_GENERATION_DEBIT: '立面生成扣点',
    TASK_SETTLEMENT_REFUND: '结算退回',
    MANUAL_CREDIT: '人工调增',
    MANUAL_DEBIT: '人工调减',
    RECHARGE_BASE_REVERSAL: '退款冲正(基础)',
    RECHARGE_BONUS_REVERSAL: '退款冲正(赠送)'
  })[t] || t

const loadStats = async () => {
  try {
    const res = await ZsApi.getAccessCodeStats()
    if (res) {
      stats.value[0].value = res.UNUSED || 0
      stats.value[1].value = res.BOUND || 0
      stats.value[2].value = res.EXPIRED || 0
      stats.value[3].value = res.DISABLED || 0
    }
  } catch {
    /* 统计卡容错 */
  }
}

const search = () => {
  query.pageNo = 1
  return load()
}

const load = async () => {
  query.status = activeTab.value === 'ALL' ? '' : activeTab.value
  loading.value = true
  loadError.value = false
  try {
    const res = await ZsApi.getAccessCodePage({ ...query })
    list.value = res?.list || []
    total.value = res?.total || 0
  } catch {
    // 加载失败必须与"没有访问码"区分，避免误判为可重新批量生成
    list.value = []
    total.value = 0
    loadError.value = true
  } finally {
    loading.value = false
  }
  loadStats()
}
onMounted(load)

// ---- 单个生成 ----
const single = reactive({
  visible: false,
  creating: false,
  validityDays: 365 as number | undefined,
  purposeNote: '',
  code: ''
})
const createSingle = async () => {
  single.creating = true
  try {
    const res = await ZsApi.createAccessCodeBatch({
      quantity: 1,
      deliveryMode: 'INLINE',
      validityDays: single.validityDays || undefined,
      purposeNote: single.purposeNote || undefined
    })
    const code = (res?.oneTimeCodes || [])[0]
    if (!code) {
      message.error('未返回明文，请重试')
      return
    }
    single.code = code
    load()
  } catch (e: any) {
    message.error(e?.msg || '生成失败，请重试')
  } finally {
    single.creating = false
  }
}
const copySingle = async () => {
  try {
    await navigator.clipboard.writeText(single.code)
    message.success('授权码已复制')
  } catch {
    message.error('复制失败，请手动选择复制')
  }
}

// ---- 详情抽屉 ----
const detail = reactive({
  visible: false,
  loading: false,
  error: '',
  row: null as any,
  account: null as any,
  orders: [] as any[]
})
const accountGrantText = computed(() => {
  const grant = (detail.account?.grants || []).find((g: any) => g.status === 'ACTIVE')
  return grant ? '已授权（可用）' : '无有效授权'
})
const payStateText = (s: string) =>
  ({
    CREATED: '已创建',
    PENDING: '待支付',
    SUCCEEDED: '成功',
    FAILED: '失败',
    UNKNOWN: '未知',
    CREDITED: '已到账',
    VOID: '已作废'
  })[s] || s
const canUnbind = computed(() => {
  if (detail.row?.status !== 'CONSUMED' || !detail.account) return false
  return (detail.account.grants || []).some((g: any) => g.status === 'ACTIVE')
})
const openDetail = (row: any) => {
  detail.row = row
  detail.account = null
  detail.orders = []
  detail.error = ''
  detail.visible = true
  loadDetail()
}
const loadDetail = async () => {
  detail.loading = true
  detail.error = ''
  try {
    if (detail.row?.boundUser) {
      detail.account = await ZsApi.getAccount(detail.row.boundUser)
      // 充值记录独立加载：订单接口失败不阻塞详情主体
      try {
        const orders = await ZsApi.getOrderPage({
          userId: String(detail.row.boundUser),
          pageNo: 1,
          pageSize: 5
        })
        detail.orders = orders?.list || []
      } catch {
        detail.orders = []
      }
    }
  } catch (e: any) {
    detail.error = e?.msg || '绑定用户信息加载失败，可关闭后重试'
  } finally {
    detail.loading = false
  }
}

const copyCode = async (row: any) => {
  try {
    const code = await ZsApi.copyAccessCode(row.id)
    await navigator.clipboard.writeText(code)
    message.success('授权码已复制')
  } catch (e: any) {
    message.error(e?.msg || '复制失败，请检查复制权限后重试')
  }
}
const doDisable = async (row: any) => {
  try {
    await ZsApi.disableAccessCode(row.id)
    message.success('已停用')
    row.status = 'DISABLED'
    load()
  } catch (e: any) {
    message.error(e?.msg || '停用失败，请重试')
  }
}
// 抽屉内停用：除刷新列表外同步重拉详情
const disableAndRefresh = async (row: any) => {
  await doDisable(row)
  await loadDetail()
}
const doDelete = async (row: any) => {
  try {
    await ElMessageBox.confirm(
      '删除后该码会立即停用并从列表隐藏，历史兑换事实仍会保留。已兑换的授权码不可删除。',
      '确认删除授权码',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    const deleted = await ZsApi.deleteAccessCode(row.id)
    if (!deleted) {
      message.warning('该授权码已兑换或已删除，不能删除')
      return
    }
    message.success('授权码已删除并停用')
    if (detail.visible && detail.row?.id === row.id) detail.visible = false
    load()
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') message.error(e?.msg || '删除失败')
  }
}
const doUnbind = async () => {
  const grant = (detail.account?.grants || []).find((g: any) => g.status === 'ACTIVE')
  if (!grant) return
  try {
    await ElMessageBox.confirm(
      '解绑后该用户将立即失去访问授权；授权码不会恢复为未使用。确定解绑？',
      '确认解绑用户',
      { type: 'warning', confirmButtonText: '解绑', cancelButtonText: '取消' }
    )
    await ZsApi.revokeAccessGrant(grant.id)
    message.success('已解绑，用户访问已撤销')
    await loadDetail()
    load()
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') message.error(e?.msg || '解绑失败，请重试')
  }
}
</script>

<style lang="scss" scoped>
.zs-footnote {
  padding: 12px 16px;
  margin-top: 16px;
  font-size: 12px;
  line-height: 1.9;
  color: #8a8a8a;
  background: #faf6f0;
  border-radius: 8px;
}

.zs-single-code {
  margin-top: 12px;

  code {
    display: block;
    padding: 10px 14px;
    margin-top: 8px;
    font-size: 16px;
    letter-spacing: 1px;
    background: #f5f3ef;
    border-radius: 6px;
    user-select: all;
  }
}

.zs-drawer-section {
  margin: 18px 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.zs-drawer-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.zs-drawer-hint {
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.8;
  color: #8a8a8a;
}
</style>
