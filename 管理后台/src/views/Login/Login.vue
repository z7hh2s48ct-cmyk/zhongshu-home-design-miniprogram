<template>
  <div :class="prefixCls" class="relative h-[100%] overflow-hidden">
    <div :class="`${prefixCls}__inner`">
      <!-- 左半幅：品牌视觉（别墅实景 + logo + 标语，来自设计稿切图） -->
      <div :class="`${prefixCls}__left`">
        <div :class="`${prefixCls}__left-bg`"></div>
      </div>
      <!-- 右半幅：浅暖灰底 + 白色登录卡片 -->
      <div :class="`${prefixCls}__right`">
        <Transition appear enter-active-class="animate__animated animate__fadeInRight">
          <div class="zs-login-wrap">
            <LoginForm />
          </div>
        </Transition>
      </div>
    </div>
  </div>
</template>
<script lang="ts" setup>
import { useDesign } from '@/hooks/web/useDesign'
import { LoginForm } from './components'

defineOptions({ name: 'Login' })

const { getPrefixCls } = useDesign()
const prefixCls = getPrefixCls('login')
</script>

<style lang="scss" scoped>
$prefix-cls: #{$namespace}-login;

.#{$prefix-cls} {
  width: 100%;
  height: 100%;

  &__inner {
    display: flex;
    width: 100%;
    height: 100%;
  }

  &__left {
    width: 50%;
    height: 100%;
    flex-shrink: 0;
  }

  &__left-bg {
    width: 100%;
    height: 100%;
    background-color: #f5eee7;
    // 切图为 3:4 竖版且 logo/标语烙在图内：cover 居中裁切会在全屏宽幅下裁掉左上角 logo。
    // contain 靠左保证完整显示，右缘渐变过渡到登录区底色，避免露出生硬拼接边。
    background-image:
      linear-gradient(to right, rgba(251, 247, 242, 0) 55%, rgba(251, 247, 242, 0.92) 94%, #fbf7f2 100%),
      url('@/assets/imgs/zs/login-left.png');
    background-position:
      left center,
      left center;
    background-repeat: no-repeat;
    background-size:
      100% 100%,
      contain;
  }

  &__right {
    display: flex;
    height: 100%;
    overflow-y: auto;
    background-color: #fbf7f2;
    flex: 1;
    align-items: center;
    justify-content: center;
  }
}

.zs-login-wrap {
  width: 90%;
  max-width: 560px;
}

@media (width <= 768px) {
  .#{$prefix-cls}__left {
    display: none;
  }
  .#{$prefix-cls}__right {
    width: 100%;
  }
}
</style>
