// 只裁剪本项目的展示入口，不删除路由、服务或修改后端授权。
const businessPermissions: Record<string, string> = {
  case: 'design:case:query',
  review: 'design:submission:review',
  'access-code': 'identity:access-code:manage',
  'recharge-plan': 'commerce:recharge-plan:query',
  'recharge-order': 'commerce:recharge-order:query',
  budget: 'design:budget:query',
  'budget-estimates': 'design:budget:query',
  'point-ledger': 'commerce:points:query',
  account: 'identity:account:query',
  'ai-job': 'aiorchestration:job:query',
  export: 'design:export:manage',
  audit: 'design:audit:query',
  // 此前遗漏该键，导致「隐私申请」页因 permission 恒为 falsy 而永不出现在菜单（只能手输 URL）。
  // 权限码与后端 PrivacyAdminController 类级 @PreAuthorize 保持一致。
  privacy: 'identity:privacy:manage',
}

const supportPaths = new Set([
  '/system/user',
  '/system/role',
  '/system/menu',
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

const supportMenus = (routes: AppRouteRecordRaw[], parent = '/'): AppRouteRecordRaw[] =>
  routes.flatMap((route) => {
    if (route.meta?.hidden || /^[a-z]+:/i.test(route.path)) return []
    const path = fullPath(parent, route.path)
    const children = supportMenus(route.children || [], path)
    if (!children.length && (route.children?.length || !supportPaths.has(path))) return []
    const title = { '/system': '系统设置', '/infra': '运维支持', '/system/user': '管理员账号' }[
      path
    ]
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
  // 复用静态业务路由，避免菜单种子缺项或相同 /zs 目录重复渲染。
  const project = business.flatMap((route) => {
    const children = (route.children || []).filter((child) => {
      const permission = businessPermissions[child.path]
      return !child.meta?.hidden && permission && can([permission])
    })
    return children.length
      ? [
          {
            ...route,
            meta: {
              ...route.meta,
              title: '业务管理',
              icon: 'ep:office-building',
              alwaysShow: true
            },
            children
          }
        ]
      : []
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
