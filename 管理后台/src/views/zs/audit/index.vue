<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">审计事件</h1>
        <div class="zs-page-subtitle"
          >全部管理操作留痕：制单复核、授权码交付、发布上架、退款与调点</div
        >
      </div>
    </div>

    <div class="zs-table-card">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column label="事件号" prop="id" width="200" />
        <el-table-column label="事件类型" width="150">
          <template #default="{ row }">{{ auditEventText(row.event_type) }}</template>
        </el-table-column>

        <el-table-column label="操作者" width="160">
          <template #default="{ row }">{{ row.actor_type }}:{{ row.actor_id }}</template>
        </el-table-column>
        <el-table-column label="动作" width="110">
          <template #default="{ row }">{{ actionText[row.action] || row.action }}</template>
        </el-table-column>
        <el-table-column label="业务对象" min-width="180">
          <template #default="{ row }"
            >{{ row.biz_type }}{{ row.biz_id ? ':' + row.biz_id : '' }}</template
          >
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <span
              class="zs-tag"
              :class="row.result === 'SUCCESS' ? 'zs-tag--green' : 'zs-tag--red'"
              >{{ auditResultText(row.result) }}</span
            >
          </template>
        </el-table-column>
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.create_time) }}</template>
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
import { fmtTime, auditEventText, auditResultText } from '@/utils/zsFormat'

const actionText: Record<string, string> = {
  EXECUTE: '执行',
  CREATE: '创建',
  REVIEW: '审核',
  APPROVE: '通过',
  REJECT: '拒绝',
  PUBLISH: '发布',
  DISABLE: '停用',
  REVOKE: '解绑',
  EXPORT: '导出',
  REDEEM: '兑换'
}

defineOptions({ name: 'ZsAudit' })

const loading = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 20 })

const search = () => {
  query.pageNo = 1
  return load()
}

const load = async () => {
  loading.value = true
  try {
    const res = await ZsApi.getAuditEvents(query)
    list.value = res?.list || []
    total.value = res?.total || 0
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>
