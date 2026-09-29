import { useCache, CACHE_KEY } from '@/hooks/web/useCache'
import { TokenType } from '@/api/login/types'

const { wsCache } = useCache()

const AccessTokenKey = 'ACCESS_TOKEN'
const RefreshTokenKey = 'REFRESH_TOKEN'

// 获取token
export const getAccessToken = () => {
  // 此处与TokenKey相同，此写法解决初始化时Cookies中不存在TokenKey报错
  const accessToken = wsCache.get(AccessTokenKey)
  return accessToken ? accessToken : wsCache.get('ACCESS_TOKEN')
}

// 刷新token
export const getRefreshToken = () => {
  return wsCache.get(RefreshTokenKey)
}

// 设置token
export const setToken = (token: TokenType) => {
  wsCache.set(RefreshTokenKey, token.refreshToken)
  wsCache.set(AccessTokenKey, token.accessToken)
}

// 删除token
export const removeToken = () => {
  wsCache.delete(AccessTokenKey)
  wsCache.delete(RefreshTokenKey)
}

/** 格式化token（jwt格式） */
export const formatToken = (token: string): string => {
  return 'Bearer ' + token
}
// ========== 账号相关 ==========

/** 获取当前登录用户编号 */
export const getCurrentUserId = (): number => {
  const user = wsCache.get(CACHE_KEY.USER)?.user
  return Number(user?.id) || 0
}

export type LoginFormType = {
  tenantName: string
  username: string
  password: string
  rememberMe: boolean
}

/**
 * 「记住我」允许持久化的字段：仅账号与租户，**刻意不含 password**。
 *
 * 安全红线：口令不做任何形式的持久化——既不明文，也不用可逆加密后再落 localStorage。
 * 本文件此前用 RSA 公钥加密口令写入缓存、并以同仓硬编码私钥在本地解密回填；
 * 私钥随 JS 分发给所有浏览器用户，使该加密形同虚设，且本地缓存可被任意脚本还原为明文口令。
 * 登录请求本身经 HTTPS 提交口令，与前端持久化无关，故移除后不影响登录链路。
 */
export type RememberedLoginFormType = Omit<LoginFormType, 'password'>

export const getLoginForm = (): RememberedLoginFormType | undefined => {
  return wsCache.get(CACHE_KEY.LoginForm)
}

export const setLoginForm = (loginForm: LoginFormType) => {
  const { tenantName, username, rememberMe } = loginForm
  wsCache.set(CACHE_KEY.LoginForm, { tenantName, username, rememberMe }, { exp: 30 * 24 * 60 * 60 })
}

export const removeLoginForm = () => {
  wsCache.delete(CACHE_KEY.LoginForm)
}

// ========== 租户相关 ==========

export const getTenantId = () => {
  // 关闭租户切换不等于关闭后端租户隔离；单租户部署不读取旧登录的租户缓存。
  if (import.meta.env.VITE_APP_TENANT_ENABLE !== 'true') {
    return import.meta.env.VITE_APP_TENANT_ID
  }
  return wsCache.get(CACHE_KEY.TenantId)
}

export const setTenantId = (tenantId: number) => {
  wsCache.set(CACHE_KEY.TenantId, tenantId)
}

export const getVisitTenantId = () => {
  return wsCache.get(CACHE_KEY.VisitTenantId)
}

export const setVisitTenantId = (visitTenantId: number) => {
  wsCache.set(CACHE_KEY.VisitTenantId, visitTenantId)
}
