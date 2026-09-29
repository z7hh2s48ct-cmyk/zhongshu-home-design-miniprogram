'use strict';

// 「添加到桌面 / 我的小程序」引导组件。
// 平台事实：微信小程序没有创建桌面图标的 JSAPI——桌面快捷方式只能由微信客户端
// 或用户手动创建，iOS 系统沙箱则完全不允许小程序落桌面（「我的小程序」是等效快捷入口）。
// 因此本组件的形态是：按平台给出操作路径的引导条 + 半屏步骤说明，不做任何虚假承诺。
const STORAGE_KEY = 'zs_desktop_guide_closed_v1';
const STEPS = {
  android: [
    { title: '打开微信首页，向下拉动露出小程序列表', detail: '在「最近使用」中找到「众墅之家」' },
    { title: '长按「众墅之家」图标', detail: '在弹出菜单中选择「添加到桌面」' }
  ],
  ios: [
    { title: '在小程序内点击右上角「···」胶囊菜单', detail: '选择「添加到我的小程序」' },
    { title: '微信首页向下拉动即可快速进入', detail: '「我的小程序」列表置顶展示，无需重新搜索' }
  ]
};

Component({
  data: { platform: 'android', steps: STEPS.android, closed: true, expanded: false },
  lifetimes: {
    attached() {
      let platform = 'android';
      try {
        const info = wx.getDeviceInfo ? wx.getDeviceInfo() : wx.getSystemInfoSync();
        // devtools 与未知平台按 android 文案展示（路径同样可用）
        platform = info && info.platform === 'ios' ? 'ios' : 'android';
      } catch (error) { /* 平台检测失败按 android 兜底 */ }
      let closed = true;
      try { closed = wx.getStorageSync(STORAGE_KEY) === true; } catch (error) { /* 读取失败先展示，用户仍可关闭 */ }
      this.setData({ platform, steps: STEPS[platform], closed });
    }
  },
  methods: {
    showSteps() { this.setData({ expanded: true }); },
    closeSheet() { this.setData({ expanded: false }); },
    dismiss() {
      this.setData({ closed: true, expanded: false });
      try { wx.setStorageSync(STORAGE_KEY, true); } catch (error) { /* 存储失败仅本次会话隐藏 */ }
    },
    keepSheet() {}
  }
});
