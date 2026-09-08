import type { RouteRecordRaw } from 'vue-router'
import { Layout } from '@/utils/routerHelper'

/**
 * 众墅之家设计管理后台——本地静态路由
 * 与效果图 02-12 对应；登录后即可见（细粒度权限由后端 @PreAuthorize 把关）
 */
const zsRouter: RouteRecordRaw[] = [
  {
    path: '/zs',
    component: Layout,
    name: 'ZhongshuDesign',
    meta: { hidden: false },
    children: [
      {
        path: 'dashboard',
        component: () => import('@/views/zs/dashboard/index.vue'),
        name: 'ZsDashboard',
        meta: { title: '工作台', icon: 'ep:home-filled', noCache: false }
      },
      {
        path: 'case',
        component: () => import('@/views/zs/case/index.vue'),
        name: 'ZsCaseManage',
        meta: { title: '户型库管理', icon: 'ep:office-building' }
      },
      {
        path: 'case/create',
        component: () => import('@/views/zs/case/create.vue'),
        name: 'ZsCaseCreate',
        meta: { title: '新增公司案例', icon: 'ep:plus', noCache: true, hidden: true }
      },
      {
        path: 'review',
        component: () => import('@/views/zs/review/index.vue'),
        name: 'ZsReview',
        meta: { title: 'AI案例审核', icon: 'ep:view', noCache: true }
      },
      {
        path: 'review/:submissionId',
        component: () => import('@/views/zs/review/detail.vue'),
        name: 'ZsReviewDetail',
        meta: { title: '审核详情', noCache: true, hidden: true, activeMenu: '/zs/review' }
      },
      {
        path: 'access-code',
        component: () => import('@/views/zs/accesscode/index.vue'),
        name: 'ZsAccessCode',
        meta: { title: '授权码管理', icon: 'ep:key', noCache: true }
      },
      {
        path: 'access-code/batch',
        component: () => import('@/views/zs/accesscode/batch.vue'),
        name: 'ZsAccessCodeBatch',
        meta: {
          title: '批量生成授权码',
          icon: 'ep:plus',
          noCache: true,
          hidden: true,
          activeMenu: '/zs/access-code'
        }
      },
      {
        path: 'recharge-plan',
        component: () => import('@/views/zs/recharge/plan/index.vue'),
        name: 'ZsRechargePlan',
        meta: { title: '充值方案', icon: 'ep:coin' }
      },
      {
        path: 'recharge-plan/edit',
        component: () => import('@/views/zs/recharge/plan-edit.vue'),
        name: 'ZsRechargePlanEdit',
        meta: {
          title: '编辑充值方案',
          noCache: true,
          hidden: true,
          activeMenu: '/zs/recharge-plan'
        }
      },
      {
        path: 'recharge-order',
        component: () => import('@/views/zs/recharge/order/index.vue'),
        name: 'ZsRechargeOrder',
        meta: { title: '充值订单', icon: 'ep:tickets' }
      },
      {
        path: 'point-ledger',
        component: () => import('@/views/zs/points/ledger/index.vue'),
        name: 'ZsPointLedger',
        meta: { title: '设计点流水', icon: 'ep:wallet' }
      },
      {
        path: 'account',
        component: () => import('@/views/zs/user/index.vue'),
        name: 'ZsAccount',
        meta: { title: 'C端用户', icon: 'ep:user' }
      },
      {
        path: 'ai-job',
        component: () => import('@/views/zs/job/index.vue'),
        name: 'ZsAiJob',
        meta: { title: 'AI任务', icon: 'ep:cpu' }
      },
      {
        path: 'export',
        component: () => import('@/views/zs/export/index.vue'),
        name: 'ZsExport',
        meta: { title: '数据导出', icon: 'ep:download' }
      },
      {
        path: 'audit',
        component: () => import('@/views/zs/audit/index.vue'),
        name: 'ZsAudit',
        meta: { title: '审计事件', icon: 'ep:document-checked' }
      }
    ]
  }
]

export default zsRouter
