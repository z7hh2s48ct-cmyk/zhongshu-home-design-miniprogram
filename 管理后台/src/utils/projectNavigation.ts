// 只裁剪本项目的展示入口，不删除路由、服务或修改后端授权。
const businessPermissions: Record<string, string> = {
  case: 'design:case:query',
  review: 'design:submission:review',
  account: 'identity:account:query',
  'access-code': 'identity:access-code:manage',
  'access-code-batches': 'identity:access-code:manage',
  'point-adjustments': 'commerce:points:query',
  recharge: 'commerce:recharge-order:query',
  'point-ledger': 'commerce:points:query',
  budget: 'design:budget:query',
  'budget-estimates': 'design:budget:query',
  'generation-pricing': 'commerce:generation-price:query',
  'usage-pricing': 'commerce:usage-price:query',
  'ai-job': 'aiorchestration:job:query',
  export: 'design:export:manage',
  audit: 'design:audit:query',
  // 此前遗漏该键，导致「隐私申请」页因 permission 恒为 falsy 而永不出现在菜单（只能手输 URL）。
  // 权限码与后端 PrivacyAdminController 类级 @PreAuthorize 保持一致。
  privacy: 'identity:privacy:manage',
  announcement: 'design:announcement:send'
}

// 业务菜单分组：与路由 meta.group 对应，顺序即侧边栏顺序（户型最上、隐私在最后组末尾）。
const businessGroups = [
  { key: 'content', title: '户型与内容', icon: 'ep:office-building' },
  { key: 'auth', title: '用户与授权', icon: 'ep:key' },
  { key: 'fund', title: '资金与点数', icon: 'ep:coin' },
  { key: 'budget', title: '预算与报价', icon: 'ep:money' },
  { key: 'pricing', title: '价格管理', icon: 'ep:price-tag' },
  { key: 'ops', title: '运营与合规', icon: 'ep:document-checked' }
] as const

// 2026-10-02 运营确认：管理员账号、菜单管理不属本业务后台，从入口裁剪；
// 上游 CRM/商城等模块菜单行的唯一可见入口即「菜单管理」页，随之不可见（底层数据保留不删）。
const supportPaths = new Set([
  '/system/role',
  '/system/log/operate-log',
  '/system/log/login-log',
  '/infra/config',
  '/infra/file/file',
  '/infra/file/file-config',
  '/infra/log/api-access-log',
  '/infra/log/api-error-log'
])

const fullPath = (parent: string, path: string) =>
  (path.startsWith('/') ? path : `${parent}/${path}`).replace(/\/{2,}/g, '/').replace(/\/$/, '') ||
  '/'

const capitalize = (value: string) => value.charAt(0).toUpperCase() + value.slice(1)

const supportMenus = (routes: AppRouteRecordRaw[], parent = '/'): AppRouteRecordRaw[] =>
  routes.flatMap((route) => {
    if (route.meta?.hidden || /^[a-z]+:/i.test(route.path)) return []
    const path = fullPath(parent, route.path)
    const children = supportMenus(route.children || [], path)
    if (!children.length && (route.children?.length || !supportPaths.has(path))) return []
    const title = { '/system': '系统设置', '/infra': '运维支持' }[path]
    return [
      {
        ...route,
        meta: { ...route.meta, ...(title ? { title } : {}) },
        ...(route.children ? { children } : {}),
        // 原目录默认页可能已被隐藏，重定向到保留的第一项。
        ...(children.length ? { redirect: fullPath(path, children[0].path) } : {})
      }
    ]
  })

export function buildProjectMenus(
  base: AppRouteRecordRaw[],
  business: AppRouteRecordRaw[],
  authorized: AppRouteRecordRaw[],
  can: (permissions: string[]) => boolean
): AppRouteRecordRaw[] {
  // 业务路由保持 /zs 扁平注册；此处仅按 meta.group 重组菜单树。
  // 分组目录 path 用 /zs-<key> 占位以保证 el-menu index 唯一；子项改写为绝对路径后可正常导航。
  const childrenOf = (business[0]?.children || []) as AppRouteRecordRaw[]
  const project = businessGroups.flatMap((group) => {
    const children = childrenOf
      .filter(
        (child) =>
          child.meta?.group === group.key &&
          !child.meta?.hidden &&
          businessPermissions[child.path] &&
          can([businessPermissions[child.path]])
      )
      .map((child) => ({ ...child, path: fullPath('/zs', child.path) }))
    if (!children.length) return []
    return [
      {
        path: `/zs-${group.key}`,
        name: `ZsGroup${capitalize(group.key)}`,
        meta: { title: group.title, icon: group.icon, alwaysShow: true },
        children
      }
    ]
  })
  return [...base.filter((route) => route.path === '/'), ...project, ...supportMenus(authorized)]
}

export function searchProjectMenus(routes: AppRouteRecordRaw[], keyword: string) {
  const query = keyword.trim().toLowerCase()
  const results: { label: string; value: string }[] = []
  if (!query) return results
  const visit = (items: AppRouteRecordRaw[], parent: string) => {
    for (const item of items) {
      if (item.meta?.hidden || /^[a-z]+:/i.test(item.path)) continue
      const path = fullPath(parent, item.path)
      if (item.children?.length) {
        visit(item.children, path)
        continue
      }
      const title = String(item.meta?.title || '')
      if (path.includes(':') || !title) continue
      if (
        `${title} ${path}`.toLowerCase().includes(query) &&
        !results.some((result) => result.value === path)
      ) {
        results.push({ label: `${title} ${path}`, value: path })
      }
    }
  }
  visit(routes, '/')
  return results
}
