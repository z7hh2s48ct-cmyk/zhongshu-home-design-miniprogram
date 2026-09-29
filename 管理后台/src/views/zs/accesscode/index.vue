<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">授权码管理</h1>
        <div class="zs-page-subtitle">通过授权码控制小程序用户准入与微信账号绑定</div>
      </div>
      <div class="zs-actions">
        <el-button @click="exportCodes">导出授权码</el-button>
        <el-button class="zs-btn-primary" @click="$router.push('/zs/access-code/batch')">
          <Icon icon="ep:plus" class="mr-4px" /> 批量生成授权码
        </el-button>
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

      <el-form inline class="zs-filter">
        <el-form-item label="授权码/用户">
          <el-input
            v-model="query.codeMask"
            placeholder="搜索掩码"
            clearable
            style="width: 200px"
            @keyup.enter="search"
          />
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
        title="授权码列表加载失败，请重试后再执行批量操作"
        style="margin-bottom: 12px"
      />

      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column type="selection" width="44" />
        <el-table-column label="授权码" prop="codeMask" width="180" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="zs-tag" :class="statusColor(row.status)">{{
              statusText(row.status)
            }}</span>
          </template>
        </el-table-column>
        <el-table-column label="绑定用户" prop="boundUser" width="120" />
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
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <span class="zs-link" v-if="row.status === 'ACTIVE'" @click="doDisable(row)">停用</span>
            <span class="zs-link-success" v-if="row.status === 'DISABLED'" @click="doEnable()"
              >启用</span
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
        <div>安全说明：无公开注册入口，授权码与绑定信息请妥善保管，禁止泄露与转售。</div>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { fmtDate, fmtTime } from '@/utils/zsFormat'

defineOptions({ name: 'ZsAccessCode' })
const message = useMessage()

const activeTab = ref('ALL')
const loading = ref(false)
/** 接口失败标记：用于把“加载失败”与“确实没有数据”区分开 */
const loadError = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const query = reactive({ status: '', codeMask: '', pageNo: 1, pageSize: 10 })

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
    const res = await ZsApi.getAccessCodePage({ ...query, codeMask: query.codeMask || undefined })
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
const reset = () => {
  query.pageNo = 1
  query.codeMask = ''
  load()
}
const doDisable = async (row: any) => {
  await ZsApi.disableAccessCode(row.id)
  message.success('已停用')
  load()
}
const doEnable = async () => {
  message.warning('已停用的授权码不可重新启用（安全规则），请生成新批次')
}
const exportCodes = () => {
  message.info('完整码仅一次交付：请从批次详情生成一次性导出票据')
}
onMounted(load)
</script>

<style lang="scss" scoped>
.zs-filter {
  margin-bottom: 6px;
}

.zs-footnote {
  padding: 12px 16px;
  margin-top: 16px;
  font-size: 12px;
  line-height: 1.9;
  color: #8a8a8a;
  background: #faf6f0;
  border-radius: 8px;
}
</style>
