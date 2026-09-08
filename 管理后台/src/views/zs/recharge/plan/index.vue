<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">充值方案</h1>
        <div class="zs-page-subtitle">小程序端展示的充值档位；改价不影响历史订单（订单引用快照）</div>
      </div>
      <el-button class="zs-btn-primary" @click="$router.push('/zs/recharge-plan/edit')">
        <Icon icon="ep:plus" class="mr-4px" /> 新增方案
      </el-button>
    </div>

    <div class="zs-table-card">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column label="方案" prop="name" min-width="140" />
        <el-table-column label="金额(元)" width="100">
          <template #default="{ row }">{{ (row.amountCents / 100).toFixed(0) }}</template>
        </el-table-column>
        <el-table-column label="基础点" prop="basePoints" width="90" />
        <el-table-column label="赠送点" prop="bonusPoints" width="90" />
        <el-table-column label="合计点数" width="100">
          <template #default="{ row }">{{ row.basePoints + row.bonusPoints }}</template>
        </el-table-column>
        <el-table-column label="推荐" width="80">
          <template #default="{ row }">
            <span class="zs-tag" v-if="row.recommended" :class="'zs-tag--orange'">推荐</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="排序" prop="sort" width="70" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <span class="zs-tag" :class="row.enabled ? 'zs-tag--green' : 'zs-tag--gray'">
              {{ row.enabled ? '启用' : '停用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <span class="zs-link" @click="$router.push(`/zs/recharge-plan/edit?id=${row.id}`)">编辑</span>
            <span class="zs-link-danger" v-if="row.enabled" @click="toggle(row, false)">停用</span>
            <span class="zs-link-success" v-else @click="toggle(row, true)">启用</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script lang="ts" setup>
import * as ZsApi from '@/api/zs'

defineOptions({ name: 'ZsRechargePlan' })
const message = useMessage()

const loading = ref(false)
const list = ref<any[]>([])

const load = async () => {
  loading.value = true
  try {
    const res = await ZsApi.getPlanPage({ pageNo: 1, pageSize: 50 })
    list.value = res?.list || res || []
  } catch {
    list.value = []
  } finally {
    loading.value = false
  }
}
const toggle = async (row: any, enabled: boolean) => {
  await ZsApi.updatePlan(row.id, { enabled })
  message.success(enabled ? '已启用' : '已停用')
  load()
}
onMounted(load)
</script>
