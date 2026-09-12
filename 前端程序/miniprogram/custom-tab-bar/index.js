"use strict";

const { openFeature } = require('../utils/access');
const unread = require('../utils/unread-count');

const TABS = [
  { text: '首页', pagePath: '/pages/home/index', icon: 'home', activeIcon: 'home-filled' },
  { text: '户型库', pagePath: '/pages/library/index', icon: 'grid-view', activeIcon: 'grid-view-filled' },
  { text: 'AI设计', pagePath: '/pages/ai-design/index', glyph: '✦' },
  { text: '我的', pagePath: '/pages/profile/index', icon: 'user', activeIcon: 'user-filled' }
];

Component({
  data: { current: 0, tabs: TABS, unreadCount: 0 },
  lifetimes: {
    attached() { this.update(); },
    ready() { this.update(); }
  },
  pageLifetimes: {
    show() { this.update(); }
  },
  methods: {
    update() {
      this.updateCurrentByRoute();
      this.updateUnreadCount();
    },
    updateCurrentByRoute() {
      const pages = getCurrentPages();
      const route = pages[pages.length - 1]?.route || '';
      const current = this.data.tabs.findIndex((tab) => tab.pagePath.slice(1) === route);
      if (current >= 0 && current !== this.data.current) this.setData({ current });
    },
    updateUnreadCount() {
      this.setData({ unreadCount: unread.getUnreadCount() });
      return unread.refreshUnreadCount()
        .then((count) => this.setData({ unreadCount: count }))
        .catch(function () { /* 静默：保留最近一次已知数量 */ });
    },
    switchTab(event) {
      const index = Number(event.currentTarget.dataset.index);
      const tab = this.data.tabs[index];
      if (!tab || index === this.data.current) return;
      openFeature(tab.pagePath);
    }
  }
});
