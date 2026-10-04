<template>
  <div :class="prefixCls" class="relative h-[100%] overflow-hidden">
    <div :class="`${prefixCls}__inner`">
      <!-- 左半幅：品牌视觉（别墅实景全幅 + HTML 品牌 logo/标语叠层，右缘软过渡） -->
      <div :class="`${prefixCls}__left`">
        <div :class="`${prefixCls}__left-bg`"></div>
        <div :class="`${prefixCls}__brand`">
          <img :src="brandLogoUrl" alt="众墅之家" />
          <span>众墅之家</span>
        </div>
        <div :class="`${prefixCls}__slogan`">让好方案，沉淀为公司的设计资产</div>
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
import brandLogo from '@/assets/imgs/zs/brand-logo.png'
import { LoginForm } from './components'

defineOptions({ name: 'Login' })

const { getPrefixCls } = useDesign()
const brandLogoUrl = brandLogo
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
    position: relative;
    width: 50%;
    height: 100%;
    flex-shrink: 0;
    overflow: hidden;
  }

  &__left-bg {
    position: absolute;
    inset: 0;
    // 干净版切图（logo/标语已修掉，改由 HTML 叠层承载）：cover 全幅铺满，照片质感完整；
    // 仅在右缘做 12% 软过渡融入登录区底色——与设计稿一致的顺滑过渡。
    background-image: url('@/assets/imgs/zs/login-left-clean.png');
    background-position: center;
    background-repeat: no-repeat;
    background-size: cover;

    &::after {
      content: '';
      position: absolute;
      inset: 0;
      // 右缘软过渡 + 底部暖白光晕（与顶部天空留白呼应的「上天下地」构图），
      // 底部 20% 渐化同时彻底消除修字补丁的任何残余痕迹
      background:
        linear-gradient(to top, rgb(251 247 242 / 90%) 0%, rgb(251 247 242 / 0%) 20%),
        linear-gradient(to right, rgb(251 247 242 / 0%) 88%, #fbf7f2 100%);
    }
  }

  &__brand {
    position: absolute;
    top: 5.5%;
    left: 5%;
    z-index: 2;
    display: flex;
    align-items: center;
    gap: 12px;

    img {
      width: 52px;
      height: 52px;
    }

    span {
      font-size: 28px;
      font-weight: 700;
      color: #6f4a28;
      letter-spacing: 3px;
    }
  }

  &__slogan {
    position: absolute;
    bottom: 6%;
    left: 5%;
    z-index: 2;
    font-size: 19px;
    font-weight: 500;
    color: #6f4a28;
    letter-spacing: 1px;
    text-shadow: 0 1px 6px rgb(251 247 242 / 65%);
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
