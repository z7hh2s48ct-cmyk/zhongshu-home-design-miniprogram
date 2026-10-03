<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">客户管理</h1>
        <div class="zs-page-subtitle">小程序用户查询：授权状态、设计点余额、设计与投稿统计</div>
      </div>
    </div>

    <div class="zs-table-card">
      <el-form inline class="zs-filter">
        <el-form-item label="昵称">
          <el-input
            v-model="query.nickname"
            placeholder="昵称关键字"
            clearable
            style="width: 180px"
            @keyup.enter="search"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="query.status"
            clearable
            placeholder="全部"
            style="width: 150px"
            @change="search"
          >
            <el-option label="正常" value="ACTIVE" />
            <el-option label="已停用" value="DISABLED" />
            <el-option label="已注销" value="CLOSED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button class="zs-btn-primary" @click="search">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="loadError"
        type="error"
        :closable="false"
        title="用户列表加载失败，请重试"
        style="margin-bottom: 12px"
      />

      <div v-if="selectedRows.length" style="margin-bottom: 10px">
        <el-button size="small" type="danger" plain @click="deleteSelected"
          >批量删除（{{ selectedRows.length }}）</el-button
        >
      </div>
      <el-table
        :data="list"
        v-loading="loading"
        stripe
        @row-click="openDetail"
        @selection-change="(rows: any[]) => (selectedRows = rows)"
      >
        <el-table-column type="selection" width="42" />
        <el-table-column label="用户编号" prop="id" width="200" />
        <el-table-column label="激活码" width="170">
          <template #default="{ row }">
            <span v-if="row.access_code_mask" class="zs-code-mask">{{ row.access_code_mask }}</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="昵称" prop="nickname" min-width="130">
          <template #default="{ row }">{{ row.nickname || '（未设置）' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="zs-tag" :class="statusClass(row.status)">{{
              statusText(row.status)
            }}</span>
          </template>
        </el-table-column>
        <el-table-column label="授权状态" width="110">
          <template #default="{ row }">
            <span class="zs-tag" :class="row.grant_id ? 'zs-tag--green' : ''">
              {{ row.grant_id ? '已激活' : '未激活' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="可用点数" prop="available_points" width="100" />
        <el-table-column label="设计项目" prop="project_count" width="90" />
        <el-table-column label="过审投稿" prop="approved_submission_count" width="90" />
        <el-table-column label="注册时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.create_time) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click.stop="openDetail(row)"
              >详情</el-button
            >
            <el-button size="small" text type="danger" @click.stop="deleteRow(row)">删除</el-button>
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

    <el-drawer v-model="detail.visible" title="用户详情" size="520px">
      <div v-loading="detail.loading">
        <template v-if="detail.data">
          <div class="zs-detail-block">
            <div class="zs-detail-row"
              ><span>用户编号</span><b>{{ detail.data.id }}</b></div
            >
            <div class="zs-detail-row">
              <span>激活码</span>
              <b>
                <span v-if="detail.data.accessCode" class="zs-code-mask">
                  {{ detail.data.accessCode.codeMask }}
                  <small style="color: #8a8a8a">
                    （{{ accessCodeStateText(detail.data.accessCode.codeStatus) }}，绑定于
                    {{ fmtTime(detail.data.accessCode.consumedAt) }}）</small
                  >
                </span>
                <span v-else>未兑换过激活码</span>
              </b>
            </div>
            <div class="zs-detail-row"
              ><span>昵称</span><b>{{ detail.data.nickname || '（未设置）' }}</b></div
            >
            <div class="zs-detail-row"
              ><span>状态</span><b>{{ statusText(detail.data.status) }}</b></div
            >
            <div class="zs-detail-row"
              ><span>可用点数</span><b>{{ detail.data.availablePoints }}</b></div
            >
            <div class="zs-detail-row">
              <span>统计</span>
              <b
                >项目 {{ detail.data.stats?.projectCount || 0 }} · 过审投稿
                {{ detail.data.stats?.approvedSubmissionCount || 0 }} · 生成任务
                {{ detail.data.stats?.aiJobCount || 0 }}</b
              >
            </div>
          </div>

          <div class="zs-detail-actions">
            <el-button
              v-hasPermi="['commerce:points:adjust']"
              type="warning"
              plain
              @click="adjustDialog.visible = true"
              >人工调点</el-button
            >
            <el-button
              v-if="detail.data.status === 'ACTIVE'"
              v-hasPermi="['identity:account:disable']"
              type="danger"
              plain
              @click="disableAccount(detail.data)"
              >停用账号</el-button
            >
          </div>
          <p v-if="detail.data.status === 'ACTIVE'" class="zs-disable-hint"
            >人工调点为该用户制单（制单/复核双人分离）；停用后立即撤销访问授权、拒绝新兑换；点数余额冻结不清零。</p
          >

          <div class="zs-detail-block-title">授权记录（解绑后该账号立即降为受限会话）</div>
          <el-table :data="detail.data.grants || []" size="small" stripe>
            <el-table-column label="授权号" prop="id" width="180" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <span
                  class="zs-tag"
                  :class="row.status === 'ACTIVE' ? 'zs-tag--green' : 'zs-tag--red'"
                >
                  {{ row.status === 'ACTIVE' ? '有效' : '已撤销' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="授权时间" width="170">
              <template #default="{ row }">{{ fmtTime(row.granted_at) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button
                  v-if="row.status === 'ACTIVE'"
                  size="small"
                  type="danger"
                  plain
                  @click="revoke(row)"
                  >解绑</el-button
                >
              </template>
            </el-table-column>
          </el-table>

          <div class="zs-detail-block-title">近期充值记录（近 5 条）</div>
          <el-table :data="detail.orders || []" size="small" stripe>
            <el-table-column label="订单号" prop="orderNo" width="150" show-overflow-tooltip />
            <el-table-column label="金额(元)" width="80">
              <template #default="{ row }">{{ (row.amountCents / 100).toFixed(2) }}</template>
            </el-table-column>
            <el-table-column label="点数" width="90">
              <template #default="{ row }">{{ row.basePoints + row.bonusPoints }}</template>
            </el-table-column>
            <el-table-column label="支付/到账" min-width="130">
              <template #default="{ row }"
                >{{ payText(row.paymentState) }}/{{ payText(row.fulfillmentState) }}</template
              >
            </el-table-column>
            <el-table-column label="时间" min-width="150">
              <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
            </el-table-column>
          </el-table>

          <div class="zs-detail-block-title">近期点数流水</div>
          <el-table :data="detail.data.recentLedger || []" size="small" stripe>
            <el-table-column label="类型" prop="type" width="180" show-overflow-tooltip />
            <el-table-column label="变动" width="80">
              <template #default="{ row }">
                <span :style="{ color: (row.delta || 0) > 0 ? '#3f9e56' : '#d0342c' }">
                  {{ (row.delta || 0) > 0 ? '+' : '' }}{{ row.delta }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="余额" prop="available_after" width="80" />
            <el-table-column label="时间" min-width="160">
              <template #default="{ row }">{{ fmtTime(row.create_time) }}</template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </el-drawer>

    <!-- 人工调点（制单）：目标用户即当前详情用户，复核在「调点复核」页双人完成 -->
    <el-dialog v-model="adjustDialog.visible" title="人工调点（直接生效）" width="460px">
      <el-form label-width="90px">
        <el-form-item label="目标用户">
          <el-input :model-value="detail.data?.id" disabled style="width: 240px" />
        </el-form-item>
        <el-form-item label="调整点数" required>
          <el-input-number v-model="adjustDialog.delta" :step="10" />
          <span class="ml-8px" style="font-size: 12px; color: #8a8a8a">正数为调增，负数为调减</span>
        </el-form-item>
        <el-form-item label="原因" required>
          <el-input
            v-model="adjustDialog.reason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="如：生成失败补偿 / 活动赠送"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustDialog.visible = false">取消</el-button>
        <el-button
          class="zs-btn-primary"
          :loading="adjustDialog.submitting"
          @click="submitAdjustment"
          >提交制单</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { fmtTime } from '@/utils/zsFormat'
import { ElMessage, ElMessageBox } from 'element-plus'

defineOptions({ name: 'ZsAccount' })

const loading = ref(false)
/** 接口失败标记：用于把"加载失败"与"确实没有数据"区分开 */
const loadError = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const query = reactive({ nickname: '', status: '', pageNo: 1, pageSize: 10 })
// 2026-10-02 运营决策：全模块可删（逻辑删除+审计）；账号删除同时撤销授权
const selectedRows = ref<any[]>([])
async function deleteRow(row: any) {
  try {
    await ElMessageBox.confirm(
      '删除该客户？删除后其授权立即撤销、账号从列表隐藏（逻辑删除，留审计），点数余额冻结不清理。',
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const ok = await ZsApi.deleteAdminData('account', row.id)
    if (!ok) {
      ElMessage.warning('该客户不存在或已删除')
      return
    }
    ElMessage.success('已删除')
    load()
  } catch (e: any) {
    ElMessage.error(e?.msg || '删除失败，请重试')
  }
}
async function deleteSelected() {
  try {
    await ElMessageBox.confirm(
      '批量删除选中的 ' +
        selectedRows.value.length +
        ' 个客户？删除后授权立即撤销（逻辑删除，留审计）。',
      '批量删除',
      { type: 'warning', confirmButtonText: '全部删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const res = await ZsApi.batchDeleteAdminData(
      'account',
      selectedRows.value.map((r) => r.id)
    )
    ElMessage.success('已删除 ' + (res?.deleted ?? 0) + ' / ' + selectedRows.value.length)
    load()
  } catch (e: any) {
    ElMessage.error(e?.msg || '批量删除失败，请重试')
  }
}
const detail = reactive({ visible: false, loading: false, data: null as any, orders: [] as any[] })

const statusText = (s: string) => ({ ACTIVE: '正常', DISABLED: '已停用', CLOSED: '已注销' })[s] || s
const statusClass = (s: string) =>
  ({ ACTIVE: 'zs-tag--green', DISABLED: 'zs-tag--yellow', CLOSED: 'zs-tag--red' })[s] || ''
const accessCodeStateText = (s: string) =>
  ({ ACTIVE: '未使用', CONSUMED: '已绑定', DISABLED: '已停用' })[s] || s

// ---- 人工调点（对当前用户制单；复核双人分离在调点复核页） ----
const adjustDialog = reactive({
  visible: false,
  submitting: false,
  delta: 10,
  reason: '',
  requestKey: '',
  requestPayload: ''
})
async function submitAdjustment() {
  const targetUserId = String(detail.data?.id || '')
  if (!/^\d{1,20}$/.test(targetUserId) || !adjustDialog.delta || !adjustDialog.reason.trim()) {
    ElMessage.warning('请填写调整点数与原因')
    return
  }
  adjustDialog.submitting = true
  try {
    const requestPayload = JSON.stringify([targetUserId, adjustDialog.delta, adjustDialog.reason.trim()])
    if (adjustDialog.requestPayload !== requestPayload) {
      adjustDialog.requestKey = crypto.randomUUID()
      adjustDialog.requestPayload = requestPayload
    }
    // 目标用户以字符串提交：19 位雪花编号超出 JS 安全整数，后端按字符串解析
    await ZsApi.createManualAdjustment({
      targetUserId,
      delta: adjustDialog.delta,
      reason: adjustDialog.reason.trim(),
      requestKey: adjustDialog.requestKey
    })
    ElMessage.success('调点已直接生效，流水与审计已入账')
    adjustDialog.visible = false
    adjustDialog.delta = 10
    adjustDialog.reason = ''
    adjustDialog.requestKey = ''
    adjustDialog.requestPayload = ''
    openDetail({ id: detail.data.id })
  } finally {
    adjustDialog.submitting = false
  }
}

const search = () => {
  query.pageNo = 1
  return load()
}

const load = async () => {
  loading.value = true
  loadError.value = false
  try {
    const res = await ZsApi.getAccountPage({
      ...query,
      nickname: query.nickname || undefined,
      status: query.status || undefined
    })
    list.value = res?.list || []
    total.value = res?.total || 0
  } catch {
    // 加载失败必须与"查无数据"区分，否则会被误读为业务上真的没有记录
    list.value = []
    total.value = 0
    loadError.value = true
  } finally {
    loading.value = false
  }
}
const reset = () => {
  query.nickname = ''
  query.status = ''
  query.pageNo = 1
  load()
}

const openDetail = async (row: any) => {
  detail.visible = true
  detail.loading = true
  detail.data = null
  detail.orders = []
  try {
    detail.data = await ZsApi.getAccount(row.id)
    // 充值记录独立加载：订单接口失败不阻塞详情主体
    try {
      const orders = await ZsApi.getOrderPage({ userId: String(row.id), pageNo: 1, pageSize: 5 })
      detail.orders = orders?.list || []
    } catch {
      detail.orders = []
    }
  } finally {
    detail.loading = false
  }
}

const payText = (s: string) =>
  ({
    CREATED: '已创建',
    PENDING: '待支付',
    SUCCEEDED: '成功',
    FAILED: '失败',
    UNKNOWN: '未知',
    CREDITED: '已到账',
    VOID: '已作废'
  })[s] || s

const disableAccount = async (row: any) => {
  try {
    await ElMessageBox.confirm(
      '停用后该用户立即失去访问授权、无法兑换新授权码；点数余额冻结但不清零。确定停用？',
      '确认停用账号',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' }
    )
    const ok = await ZsApi.disableAccount(row.id)
    if (!ok) {
      ElMessage.warning('该账号已停用或已注销，无需重复操作')
      return
    }
    ElMessage.success('已停用，访问授权已撤销')
    load()
    openDetail({ id: row.id })
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e?.msg || '停用失败，请重试')
  }
}

const revoke = async (grant: any) => {
  await ElMessageBox.confirm(
    `撤销授权 ${grant.id}？撤销后该用户立即失去全部业务功能，需重新兑换授权码激活。`,
    '解绑确认',
    { type: 'warning', confirmButtonText: '确认解绑', cancelButtonText: '取消' }
  )
  await ZsApi.revokeAccessGrant(grant.id)
  ElMessage.success('已解绑')
  openDetail({ id: detail.data.id })
}

onMounted(load)
</script>

<style lang="scss" scoped>
.zs-filter {
  margin-bottom: 6px;
}

.zs-detail-block {
  padding: 12px 16px;
  margin-bottom: 16px;
  background: #faf6f0;
  border-radius: 8px;
}

.zs-detail-row {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  font-size: 13px;

  span {
    color: #8a8a8a;
  }
}

.zs-detail-block-title {
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: #6a3b1b;
}

.zs-disable-hint {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.8;
  color: #8a8a8a;
}
</style>

<style lang="scss" scoped>
.zs-code-mask {
  font-family: monospace;
}

.zs-detail-actions {
  display: flex;
  gap: 8px;
  margin: 10px 0;
}
</style>
