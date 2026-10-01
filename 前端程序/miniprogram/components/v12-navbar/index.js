"use strict";

Component({
  properties: {
    title: { type: String, value: '' },
    showBack: { type: Boolean, value: false },
    showBrand: { type: Boolean, value: true }
  },
  data: {
    statusBarHeight: 20,
    menuHeight: 44,
    navigationHeight: 64
  },
  lifetimes: {
    attached() {
      const windowInfo = typeof wx.getWindowInfo === 'function'
        ? wx.getWindowInfo()
        : wx.getSystemInfoSync();
      // P2-5（报告 15）：胶囊尺寸 API 在部分环境（开发者工具/自动化/低版本基础库）会抛
      // getMenuButtonBoundingClientRect:fail，未防护时每页加载必报 TypeError——失败即走
      // data 里的默认导航高度兜底（20/44/64），不再让异常冒泡。
      let statusBarHeight = windowInfo.statusBarHeight || 20;
      let menuHeight = 44;
      try {
        const menuRect = wx.getMenuButtonBoundingClientRect();
        const capsuleHeight = (menuRect && menuRect.height) || 32;
        const verticalGap = Math.max(0, ((menuRect && menuRect.top) || statusBarHeight + 4) - statusBarHeight);
        menuHeight = capsuleHeight + verticalGap * 2;
      } catch (e) {
        menuHeight = 44;
      }
      this.setData({
        statusBarHeight,
        menuHeight,
        navigationHeight: statusBarHeight + menuHeight
      });
    }
  },
  methods: {
    goBack() {
      wx.navigateBack({ fail: () => wx.switchTab({ url: '/pages/home/index' }) });
    }
  }
});
