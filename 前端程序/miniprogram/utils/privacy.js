'use strict';

/**
 * 微信隐私协议授权封装。
 *
 * 背景：小程序新隐私规范要求，用户未同意隐私协议前，隐私相关接口（手机号、位置等）调用会被拦截。
 * 本模块统一封装「是否需授权」探测与「拉起授权」两个动作，供 app 启动与后续接入隐私接口时复用。
 *
 * 设计红线：
 * 1. **绝不 reject、绝不抛错**：授权是体验前置，不能让基础库差异或用户拒绝把页面打挂；
 * 2. **基础库兼容**：wx.getPrivacySetting（2.32.3+）与 wx.requirePrivacyAuthorize（2.32.3+）
 *    均做存在性判断；旧基础库缺少 API 时「按无需授权 / 已授权」放行，避免功能被静默锁死；
 * 3. **拒绝不等于退出**：用户拒绝授权时仅返回 false，由调用方决定限制哪部分功能，
 *    不得强制退出或阻断非隐私功能；
 * 4. 不在此模块内做 UI：微信原生隐私弹窗由 requirePrivacyAuthorize 触发，
 *    自定义弹窗如需接入，用 onNeedAuthorization 注册回调。
 */

function api() {
  return typeof wx === 'undefined' ? null : wx;
}

/**
 * 查询是否需要用户授权隐私协议。
 * @returns {Promise<boolean>} 需要授权返回 true；不需要、不可判定或调用失败均返回 false。
 */
function needAuthorization() {
  return new Promise(function (resolve) {
    const runtime = api();
    if (!runtime || typeof runtime.getPrivacySetting !== 'function') {
      resolve(false);
      return;
    }
    try {
      runtime.getPrivacySetting({
        success: function (res) {
          resolve(Boolean(res && res.needAuthorization));
        },
        fail: function () {
          resolve(false);
        }
      });
    } catch (error) {
      resolve(false);
    }
  });
}

/**
 * 拉起隐私协议授权弹窗。
 * @returns {Promise<boolean>} 用户同意返回 true；拒绝返回 false；不支持该 API 时返回 true（按已授权放行）。
 */
function authorize() {
  return new Promise(function (resolve) {
    const runtime = api();
    if (!runtime || typeof runtime.requirePrivacyAuthorize !== 'function') {
      resolve(true);
      return;
    }
    try {
      runtime.requirePrivacyAuthorize({
        success: function () {
          resolve(true);
        },
        fail: function () {
          resolve(false);
        }
      });
    } catch (error) {
      resolve(false);
    }
  });
}

/**
 * 组合入口：需要授权才拉起。
 * @returns {Promise<boolean>} 最终是否处于「已获授权」状态。
 */
function ensure() {
  return needAuthorization().then(function (needed) {
    return needed ? authorize() : true;
  });
}

module.exports = { needAuthorization, authorize, ensure };
