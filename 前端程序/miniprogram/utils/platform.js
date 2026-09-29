'use strict';

/**
 * 运行平台判定工具。
 *
 * 用途：iOS 端禁止以微信支付购买虚拟商品（苹果政策 + 微信审核要求），
 * 充值「设计点」属虚拟货币，故充值页需按平台隐藏支付入口。
 *
 * 设计要点：
 * 1. 优先使用新基础库的 wx.getDeviceInfo（avoid wx.getSystemInfoSync 的废弃路径），
 *    不可用时回退 getSystemInfoSync；
 * 2. 任何一层失败（非微信环境、基础库过旧、API 被裁剪）都**不抛异常**，
 *    统一返回 'unknown' 并按"非 iOS"处理——避免因取平台失败而整体阻断充值功能；
 * 3. 不缓存结果：页面每次进入重新判定，兼容开发者工具切换机型。
 */

const UNKNOWN = 'unknown';

function normalize(platform) {
  return typeof platform === 'string' && platform ? platform.toLowerCase() : UNKNOWN;
}

function currentPlatform() {
  try {
    if (typeof wx === 'undefined') return UNKNOWN;

    if (typeof wx.getDeviceInfo === 'function') {
      const device = wx.getDeviceInfo();
      if (device && device.platform) return normalize(device.platform);
    }

    if (typeof wx.getSystemInfoSync === 'function') {
      const info = wx.getSystemInfoSync();
      if (info && info.platform) return normalize(info.platform);
    }
  } catch (error) {
    // 取平台失败不应阻断页面；保持保守默认值。
  }
  return UNKNOWN;
}

function isIOS() {
  return currentPlatform() === 'ios';
}

module.exports = { currentPlatform, isIOS };
