<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">授权码批次</h1>
        <div class="zs-page-subtitle"
          >批次级视图：数量、已兑换、交付方式与用途；点「查看授权码」按批次筛码</div
        >
      </div>
      <el-button class="zs-btn-primary" @click="$router.push('/zs/access-code/batch')">
        <Icon icon="ep:plus" class="mr-4px" /> 批量生成授权码
      </el-button>
    </div>

    <div class="zs-table-card">
      <el-alert
        v-if="loadError"
        type="error"
        :closable="false"
        title="批次加载失败，请重试"
        style="margin-bottom: 12px"
        ><el-button @click="load">重新加载</el-button></el-alert
      >
      <el-alert
        v-if="!loading && !loadError && !list.length"
        type="info"
        :closable="false"
        title="还没有授权码批次；点击右上角批量生成创建第一批"
        style="margin-bottom: 12px"
      />

      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column label="批次号" prop="id" width="190" />
        <el-table-column label="数量" prop="quantity" width="80" />
        <el-table-column label="已兑换" width="90">
          <template #default="{ row }">
            <span
              class="zs-tag"
              :class="row.redeemedCount >= row.quantity ? 'zs-tag--green' : 'zs-tag--blue'"
              >{{ row.redeemedCount }}/{{ row.quantity }}</span
            >
          </template>
        </el-table-column>
        <el-table-column label="交付方式" width="100">
          <template #default="{ row }">{{
            row.deliveryMode === 'TICKET' ? '加密票据' : '明文即时'
          }}</template>
        </el-table-column>
        <el-table-column label="明文暴露数" prop="exposedCount" width="95" />
        <el-table-column
          label="用途备注"
          prop="purposeNote"
          min-width="150"
          show-overflow-tooltip
        />
        <el-table-column label="发行人" prop="issuedBy" width="110" />
        <el-table-column label="有效期至" width="120">
          <template #default="{ row }">{{
            row.expiresAt ? fmtDate(row.expiresAt) : '长期'
          }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <span
              class="zs-link"
              @click="$router.push({ path: '/zs/access-code', query: { batchId: row.id } })"
              >查看授权码 ›</span
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
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'
import { fmtDate, fmtTime } from '@/utils/zsFormat'

defineOptions({ name: 'ZsAccessCodeBatches' })

const loading = ref(false)
const loadError = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 20 })

const search = () => {
  query.pageNo = 1
  return load()
}

const load = async () => {
  loading.value = true
  loadError.value = false
  try {
    const res = await ZsApi.getBatchPage({ ...query })
    list.value = res?.list || []
    total.value = res?.total || 0
  } catch {
    list.value = []
    total.value = 0
    loadError.value = true
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>
