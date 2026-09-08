"use strict";

const { openFeature } = require('../../utils/access');

Component({
  properties: {
    title: { type: String, value: '' },
    showBack: { type: Boolean, value: false },
    showBrand: { type: Boolean, value: true },
    showNotice: { type: Boolean, value: false }
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
      const menuRect = wx.getMenuButtonBoundingClientRect();
      const statusBarHeight = windowInfo.statusBarHeight || 20;
      const capsuleHeight = menuRect.height || 32;
      const verticalGap = Math.max(0, menuRect.top - statusBarHeight);
      const menuHeight = capsuleHeight + verticalGap * 2;
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
    },
    openMessages() {
      openFeature('/pages/messagecenter/messagecenter');
    }
  }
});
