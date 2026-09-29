<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">C端用户</h1>
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

      <el-table :data="list" v-loading="loading" stripe @row-click="openDetail">
        <el-table-column label="用户编号" prop="id" width="200" />
        <el-table-column label="昵称" prop="nickname" min-width="140">
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
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click.stop="openDetail(row)"
              >详情</el-button
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
    </div>

    <el-drawer v-model="detail.visible" title="用户详情" size="520px">
      <div v-loading="detail.loading">
        <template v-if="detail.data">
          <div class="zs-detail-block">
            <div class="zs-detail-row"
              ><span>用户编号</span><b>{{ detail.data.id }}</b></div
            >
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
const detail = reactive({ visible: false, loading: false, data: null as any })

const statusText = (s: string) => ({ ACTIVE: '正常', DISABLED: '已停用', CLOSED: '已注销' })[s] || s
const statusClass = (s: string) =>
  ({ ACTIVE: 'zs-tag--green', DISABLED: 'zs-tag--yellow', CLOSED: 'zs-tag--red' })[s] || ''

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
  try {
    detail.data = await ZsApi.getAccount(row.id)
  } finally {
    detail.loading = false
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
</style>
