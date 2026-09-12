'use strict';

const api = require('./api');
const { isAuthorized } = require('./access');
let refreshPromise = null;

function normalize(value) {
  const count = Number(value);
  return Number.isFinite(count) && count > 0 ? Math.floor(count) : 0;
}

function getUnreadCount() {
  const app = typeof getApp === 'function' ? getApp() : null;
  return normalize(app && app.globalData && app.globalData.unreadCount);
}

function setUnreadCount(value) {
  const count = normalize(value);
  const app = typeof getApp === 'function' ? getApp() : null;
  if (app && app.globalData) app.globalData.unreadCount = count;
  // A cached custom TabBar does not reliably receive pageLifetimes.show on navigateBack.
  const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
  pages.forEach((page) => {
    const tab = typeof page.getTabBar === 'function' ? page.getTabBar() : null;
    if (tab && typeof tab.setData === 'function') tab.setData({ unreadCount: count });
  });
  return count;
}

function refreshUnreadCount() {
  if (!isAuthorized()) return Promise.resolve(setUnreadCount(0));
  if (refreshPromise) return refreshPromise;
  refreshPromise = api.getUnreadCount().then(
    (count) => {
      refreshPromise = null;
      return setUnreadCount(count);
    },
    (error) => {
      refreshPromise = null;
      throw error;
    }
  );
  return refreshPromise;
}

function decrementUnreadCount() {
  return setUnreadCount(Math.max(getUnreadCount() - 1, 0));
}

module.exports = { getUnreadCount, setUnreadCount, refreshUnreadCount, decrementUnreadCount };
