import type { RouteRecordRaw } from 'vue-router'
import { Layout } from '@/utils/routerHelper'

/**
 * 众墅之家设计管理后台——本地静态路由
 * meta.group 决定侧边栏分组：projectNavigation.buildProjectMenus 按其组装 6 个业务顶级组
 * （content 户型与内容 / auth 用户与授权 / fund 资金与点数 / budget 预算与报价 / pricing 价格管理 / ops 运营与合规）。
 * 路由本身保持 /zs 扁平注册，分组只影响菜单树展示。
 * 登录后即可见（细粒度权限由后端 @PreAuthorize 把关）
 */
const zsRouter: RouteRecordRaw[] = [
  {
    path: '/zs',
    component: Layout,
    name: 'ZhongshuDesign',
    meta: { hidden: false },
    children: [
      // —— 户型与内容 content ——
      {
        path: 'case',
        component: () => import('@/views/zs/case/index.vue'),
        name: 'ZsCaseManage',
        meta: { title: '户型库管理', icon: 'ep:office-building', group: 'content' }
      },
      {
        path: 'case/create',
        component: () => import('@/views/zs/case/create.vue'),
        name: 'ZsCaseCreate',
        meta: {
          title: '新增公司案例',
          icon: 'ep:plus',
          noCache: true,
          hidden: true,
          group: 'content',
          activeMenu: '/zs/case'
        }
      },
      {
        path: 'case/detail/:caseId',
        component: () => import('@/views/zs/case/detail.vue'),
        name: 'ZsCaseDetail',
        meta: {
          title: '案例详情',
          noCache: true,
          hidden: true,
          group: 'content',
          activeMenu: '/zs/case'
        }
      },
      {
        path: 'review',
        component: () => import('@/views/zs/review/index.vue'),
        name: 'ZsReview',
        meta: { title: 'AI案例审核', icon: 'ep:view', noCache: true, group: 'content' }
      },
      {
        path: 'review/:submissionId',
        component: () => import('@/views/zs/review/detail.vue'),
        name: 'ZsReviewDetail',
        meta: {
          title: '审核详情',
          noCache: true,
          hidden: true,
          group: 'content',
          activeMenu: '/zs/review'
        }
      },
      // —— 用户与授权 auth ——
      {
        path: 'account',
        component: () => import('@/views/zs/user/index.vue'),
        name: 'ZsAccount',
        meta: { title: '客户管理', icon: 'ep:user', group: 'auth' }
      },
      {
        path: 'access-code',
        component: () => import('@/views/zs/accesscode/index.vue'),
        name: 'ZsAccessCode',
        meta: { title: '激活码管理', icon: 'ep:key', noCache: true, group: 'auth' }
      },
      {
        path: 'access-code-batches',
        redirect: '/zs/access-code?view=batches',
        name: 'ZsAccessCodeBatchesLegacy',
        meta: { hidden: true, group: 'auth', activeMenu: '/zs/access-code' }
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
          group: 'auth',
          activeMenu: '/zs/access-code'
        }
      },
      {
        path: 'point-adjustments',
        component: () => import('@/views/zs/points/adjustments.vue'),
        name: 'ZsPointAdjustments',
        meta: { title: '点数调整审核', icon: 'ep:finished', noCache: true, group: 'auth' }
      },
      // —— 资金与点数 fund ——
      {
        path: 'recharge',
        component: () => import('@/views/zs/recharge/index.vue'),
        name: 'ZsRechargeManage',
        meta: { title: '充值管理', icon: 'ep:tickets', group: 'fund' }
      },
      {
        path: 'recharge-order',
        name: 'ZsRechargeOrderLegacy',
        redirect: '/zs/recharge?tab=orders',
        meta: { hidden: true, group: 'fund', activeMenu: '/zs/recharge' }
      },
      {
        path: 'recharge-plan',
        name: 'ZsRechargePlanLegacy',
        redirect: '/zs/recharge?tab=plans',
        meta: { hidden: true, group: 'fund', activeMenu: '/zs/recharge' }
      },
      {
        path: 'recharge-plan/edit',
        component: () => import('@/views/zs/recharge/plan-edit.vue'),
        name: 'ZsRechargePlanEdit',
        meta: {
          title: '编辑充值方案',
          noCache: true,
          hidden: true,
          group: 'fund',
          activeMenu: '/zs/recharge'
        }
      },
      {
        path: 'point-ledger',
        component: () => import('@/views/zs/points/ledger/index.vue'),
        name: 'ZsPointLedger',
        meta: { title: '点数流水', icon: 'ep:wallet', group: 'fund' }
      },
      // —— 预算与报价 budget ——
      {
        path: 'budget',
        component: () => import('@/views/zs/budget/index.vue'),
        name: 'ZsBudgetCatalog',
        meta: { title: '预算配置', icon: 'ep:money', noCache: true, group: 'budget' }
      },
      {
        path: 'budget-estimates',
        component: () => import('@/views/zs/budget/estimates.vue'),
        name: 'ZsBudgetEstimates',
        meta: { title: '项目预算', icon: 'ep:document', noCache: true, group: 'budget' }
      },
      {
        path: 'budget-estimates/:budgetId',
        component: () => import('@/views/zs/budget/detail.vue'),
        name: 'ZsBudgetDetail',
        meta: {
          title: '预算明细与修订',
          noCache: true,
          hidden: true,
          group: 'budget',
          activeMenu: '/zs/budget-estimates'
        }
      },
      {
        path: 'budget-estimates/:budgetId/quotes',
        component: () => import('@/views/zs/budget/quote.vue'),
        name: 'ZsBudgetQuote',
        meta: {
          title: '对外报价',
          noCache: true,
          hidden: true,
          group: 'budget',
          activeMenu: '/zs/budget-estimates'
        }
      },
      // —— 价格管理 pricing ——
      {
        path: 'generation-pricing',
        component: () => import('@/views/zs/generation-pricing/index.vue'),
        name: 'ZsGenerationPricing',
        meta: { title: '出图定价', icon: 'ep:price-tag', noCache: true, group: 'pricing' }
      },
      {
        path: 'usage-pricing',
        component: () => import('@/views/zs/usage-pricing/index.vue'),
        name: 'ZsUsagePricing',
        meta: { title: '服务计费', icon: 'ep:coin', noCache: true, group: 'pricing' }
      },
      // —— 运营与合规 ops ——
      {
        path: 'ai-job',
        redirect: '/zs/point-ledger?tab=jobs',
        name: 'ZsAiJobLegacy',
        meta: { hidden: true, group: 'ops', activeMenu: '/zs/point-ledger' }
      },
      {
        path: 'export',
        component: () => import('@/views/zs/export/index.vue'),
        name: 'ZsExport',
        meta: { title: '数据导出', icon: 'ep:download', group: 'ops' }
      },
      {
        path: 'audit',
        component: () => import('@/views/zs/audit/index.vue'),
        name: 'ZsAudit',
        meta: { title: '操作记录', icon: 'ep:document-checked', group: 'ops' }
      },
      {
        path: 'announcement',
        component: () => import('@/views/zs/announcement/index.vue'),
        name: 'ZsAnnouncement',
        meta: { title: '运营公告', icon: 'ep:message', group: 'ops' }
      },
      {
        path: 'privacy',
        component: () => import('@/views/zs/privacy/index.vue'),
        name: 'ZsPrivacy',
        meta: { title: '隐私申请', icon: 'ep:lock', group: 'ops' }
      },
      {
        path: 'dashboard',
        component: () => import('@/views/zs/dashboard/index.vue'),
        name: 'ZsDashboard',
        // 与首页 / 指向同一视图，仅作 URL 兜底；工作台入口由 remainingRouter 的 / 承载。
        meta: { title: '工作台', icon: 'ep:home-filled', noCache: false, hidden: true }
      }
    ]
  }
]

export default zsRouter
