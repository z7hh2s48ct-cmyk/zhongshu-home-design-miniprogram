<template>
  <div class="zs-login-card">
    <!-- 卡片标题区 -->
    <div class="zs-login-head">
      <div class="zs-login-title">众墅之家设计管理后台</div>
      <div class="zs-login-subtitle">仅限授权管理员使用</div>
    </div>

    <el-form
      v-show="getShow"
      ref="formLogin"
      :model="loginData.loginForm"
      :rules="LoginRules"
      class="zs-login-form"
      label-position="top"
      size="large"
    >
      <div class="zs-field">
        <div class="zs-field-label">管理员账号</div>
        <el-form-item prop="username">
          <el-input
            v-model="loginData.loginForm.username"
            placeholder="请输入管理员账号"
            :prefix-icon="iconAvatar"
          />
        </el-form-item>
      </div>

      <div class="zs-field">
        <div class="zs-field-label">登录密码</div>
        <el-form-item prop="password">
          <el-input
            v-model="loginData.loginForm.password"
            placeholder="请输入登录密码"
            :prefix-icon="iconLock"
            show-password
            type="password"
            @keyup.enter="getCode()"
          />
        </el-form-item>
      </div>

      <div class="zs-remember-row">
        <el-checkbox v-model="loginData.loginForm.rememberMe">记住账号</el-checkbox>
        <el-link
          class="zs-forget"
          :underline="false"
          @click="setLoginState(LoginStateEnum.RESET_PASSWORD)"
        >
          忘记密码
        </el-link>
      </div>

      <el-button
        :loading="loginLoading"
        class="zs-login-btn"
        @click="getCode()"
      >
        登录后台
      </el-button>

      <Verify
        v-if="loginData.captchaEnable === 'true'"
        ref="verify"
        :captchaType="captchaType"
        :imgSize="{ width: '400px', height: '200px' }"
        mode="pop"
        @success="handleLogin"
      />

      <div class="zs-footer">如需开通管理员权限，请联系系统负责人</div>
    </el-form>

    <!-- 忘记密码弹层（保留底座能力，视觉沿用设计语言） -->
    <ForgetPasswordForm />
  </div>
</template>
<script lang="ts" setup>
import { ElLoading } from 'element-plus'
import type { RouteLocationNormalizedLoaded } from 'vue-router'

import { useIcon } from '@/hooks/web/useIcon'

import * as authUtil from '@/utils/auth'
import { usePermissionStore } from '@/store/modules/permission'
import * as LoginApi from '@/api/login'
import { LoginStateEnum, useFormValid, useLoginState } from './useLogin'
import ForgetPasswordForm from './ForgetPasswordForm.vue'

defineOptions({ name: 'LoginForm' })

const iconAvatar = useIcon({ icon: 'ep:avatar' })
const iconLock = useIcon({ icon: 'ep:lock' })
const formLogin = ref()
const { validForm } = useFormValid(formLogin)
const { setLoginState, getLoginState } = useLoginState()
const { currentRoute, push } = useRouter()
const permissionStore = usePermissionStore()
const redirect = ref<string>('')
const loginLoading = ref(false)
const verify = ref()
const captchaType = ref('blockPuzzle')

const getShow = computed(() => unref(getLoginState) === LoginStateEnum.LOGIN)

const LoginRules = {
  username: [required],
  password: [required]
}
const loginData = reactive({
  isShowPassword: false,
  captchaEnable: import.meta.env.VITE_APP_CAPTCHA_ENABLE,
  tenantEnable: import.meta.env.VITE_APP_TENANT_ENABLE,
  loginForm: {
    tenantName: import.meta.env.VITE_APP_DEFAULT_LOGIN_TENANT || '',
    username: import.meta.env.VITE_APP_DEFAULT_LOGIN_USERNAME || '',
    password: import.meta.env.VITE_APP_DEFAULT_LOGIN_PASSWORD || '',
    captchaVerification: '',
    rememberMe: true // 默认记录我。如果不需要，可手动修改
  }
})

// 获取验证码
const getCode = async () => {
  // 情况一，未开启：则直接登录
  if (loginData.captchaEnable === 'false') {
    await handleLogin({})
  } else {
    // 情况二，已开启：则展示验证码；只有完成验证码的情况，才进行登录
    // 弹出验证码
    verify.value.show()
  }
}
// 获取租户 ID
const getTenantId = async () => {
  if (loginData.tenantEnable === 'true') {
    const res = await LoginApi.getTenantIdByName(loginData.loginForm.tenantName)
    authUtil.setTenantId(res)
  }
}
// 记住我
const getLoginFormCache = () => {
  const loginForm = authUtil.getLoginForm()
  if (loginForm) {
    loginData.loginForm = {
      ...loginData.loginForm,
      username: loginForm.username ? loginForm.username : loginData.loginForm.username,
      password: loginForm.password ? loginForm.password : loginData.loginForm.password,
      rememberMe: loginForm.rememberMe,
      tenantName: loginForm.tenantName ? loginForm.tenantName : loginData.loginForm.tenantName
    }
  }
}
// 根据域名，获得租户信息
const getTenantByWebsite = async () => {
  if (loginData.tenantEnable === 'true') {
    const website = location.host
    const res = await LoginApi.getTenantByWebsite(website)
    if (res) {
      loginData.loginForm.tenantName = res.name
      authUtil.setTenantId(res.id)
    }
  }
}
const loading = ref() // ElLoading.service 返回的实例
// 登录
const handleLogin = async (params: any) => {
  loginLoading.value = true
  try {
    await getTenantId()
    const data = await validForm()
    if (!data) {
      return
    }
    const loginDataLoginForm = { ...loginData.loginForm }
    loginDataLoginForm.captchaVerification = params.captchaVerification
    const res = await LoginApi.login(loginDataLoginForm)
    if (!res) {
      return
    }
    loading.value = ElLoading.service({
      lock: true,
      text: '正在加载系统中...',
      background: 'rgba(0, 0, 0, 0.7)'
    })
    if (loginDataLoginForm.rememberMe) {
      authUtil.setLoginForm(loginDataLoginForm)
    } else {
      authUtil.removeLoginForm()
    }
    authUtil.setToken(res)
    if (!redirect.value) {
      redirect.value = '/'
    }
    // 判断是否为SSO登录
    if (redirect.value.indexOf('sso') !== -1) {
      window.location.href = window.location.href.replace('/login?redirect=', '')
    } else {
      await push({ path: redirect.value || permissionStore.addRouters[0].path })
    }
  } finally {
    loginLoading.value = false
    loading.value.close()
  }
}

watch(
  () => currentRoute.value,
  (route: RouteLocationNormalizedLoaded) => {
    redirect.value = route?.query?.redirect as string
  },
  {
    immediate: true
  }
)
onMounted(() => {
  getLoginFormCache()
  getTenantByWebsite()
})
</script>

<style lang="scss" scoped>
.zs-login-card {
  background: #fefefe;
  border-radius: 12px;
  box-shadow: 0 8px 40px rgb(113 67 32 / 8%);
  padding: 56px 64px 40px;
}

.zs-login-head {
  text-align: center;
  margin-bottom: 40px;

  .zs-login-title {
    font-size: 28px;
    font-weight: 700;
    color: #282728;
    letter-spacing: 1px;
  }

  .zs-login-subtitle {
    margin-top: 10px;
    font-size: 14px;
    color: #9b9b9b;
  }
}

.zs-field {
  margin-bottom: 18px;

  .zs-field-label {
    font-size: 14px;
    font-weight: 600;
    color: #282728;
    margin-bottom: 8px;
  }

  :deep(.el-input__wrapper) {
    border-radius: 6px;
    box-shadow: 0 0 0 1px #d9d5cf inset;
    padding: 4px 12px;
  }

  :deep(.el-input__inner) {
    height: 36px;
  }
}

.zs-remember-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 2px 0 22px;

  :deep(.el-checkbox__label) {
    color: #282728;
    font-size: 14px;
  }

  :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
    background-color: #714320;
    border-color: #714320;
  }

  :deep(.el-checkbox__inner:hover) {
    border-color: #714320;
  }

  .zs-forget {
    font-size: 14px;
    color: #714320;

    &:hover {
      color: #8a5a34;
    }
  }
}

.zs-login-btn {
  width: 100%;
  height: 48px;
  font-size: 16px;
  font-weight: 600;
  color: #fff;
  background: #714320;
  border: none;
  border-radius: 6px;
  letter-spacing: 2px;

  &:hover,
  &:focus {
    background: #82522f;
    color: #fff;
  }

  &.is-loading {
    background: #714320;
  }
}

.zs-footer {
  margin-top: 34px;
  text-align: center;
  font-size: 13px;
  color: #9b9b9b;
}
</style>
