<template>
  <div class="zs-page">
    <div class="zs-page-header">
      <div>
        <h1 class="zs-page-title">充值管理</h1>
        <div class="zs-page-subtitle"
          >订单与档位合并管理；改价请新增方案，历史订单引用快照不受影响</div
        >
      </div>
    </div>

    <div class="zs-table-card">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="充值订单" name="orders" />
        <el-tab-pane label="充值方案" name="plans" />
      </el-tabs>
      <RechargeOrder v-if="activeTab === 'orders'" embedded />
      <RechargePlan v-else embedded />
    </div>
  </div>
</template>

<script lang="ts" setup>
import RechargeOrder from './order/index.vue'
import RechargePlan from './plan/index.vue'

defineOptions({ name: 'ZsRechargeManage' })
const route = useRoute()
// 支持 /zs/recharge?tab=plans 直达方案页（工作台快捷入口、旧链接兼容）
const activeTab = ref(route.query.tab === 'plans' ? 'plans' : 'orders')
</script>
