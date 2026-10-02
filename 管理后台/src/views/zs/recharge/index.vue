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
      <el-tabs
        v-model="activeTab"
        :class="{
          'zs-recharge--no-orders': !canViewOrders,
          'zs-recharge--no-plans': !canViewPlans
        }"
      >
        <el-tab-pane label="充值订单" name="orders" />
        <el-tab-pane label="充值方案" name="plans" />
      </el-tabs>
      <RechargeOrder v-if="activeTab === 'orders' && canViewOrders" embedded />
      <RechargePlan v-else-if="activeTab === 'plans' && canViewPlans" embedded />
      <el-empty v-else description="No recharge access" />
    </div>
  </div>
</template>

<script lang="ts" setup>
import RechargeOrder from './order/index.vue'
import RechargePlan from './plan/index.vue'
import { hasPermission } from '@/directives/permission/hasPermi'

defineOptions({ name: 'ZsRechargeManage' })
const route = useRoute()
// 支持 /zs/recharge?tab=plans 直达方案页（工作台快捷入口、旧链接兼容）
const canViewOrders = computed(() => hasPermission(['commerce:recharge-order:query']))
const canViewPlans = computed(() => hasPermission(['commerce:recharge-plan:query']))
const defaultTab = () =>
  canViewPlans.value && route.query.tab === 'plans'
    ? 'plans'
    : canViewOrders.value
      ? 'orders'
      : canViewPlans.value
        ? 'plans'
        : ''
const activeTab = ref(defaultTab())
watch([canViewOrders, canViewPlans], () => {
  if (
    (activeTab.value === 'orders' && !canViewOrders.value) ||
    (activeTab.value === 'plans' && !canViewPlans.value)
  )
    activeTab.value = defaultTab()
})
</script>

<style scoped>
.zs-recharge--no-orders :deep(.el-tabs__item:nth-child(1)),
.zs-recharge--no-plans :deep(.el-tabs__item:nth-child(2)) {
  display: none;
}
</style>
