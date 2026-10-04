'use strict';

/**
 * 环境配置（上线基线）。
 *
 * env 与 apiBase 必须保持一致，二者共同决定运行时行为：
 *   - env === 'prod'：图片等资产只接受 https 短期签名地址；收到非 https 地址会立即失败，
 *     避免误把本地开发内容端点当作线上通道（见 utils/assets.js#fetchAssetDataUrl）。
 *   - env === 'dev' ：允许 local:// 开发内容端点，仅在本地联调时使用。
 *
 * 本地联调时，把下面两项一起改为：apiBase: 'http://localhost:48080'、env: 'dev'，
 * 并在微信开发者工具「详情 → 本地设置」勾选「不校验合法域名」。上线前务必改回 prod。
 */
const config = {
  // 正式环境后端域名（与 zszhj.com 管理后台同源部署）
  apiBase: 'https://api.zszhj.com',
  env: 'prod',
  tenantId: 1
};

module.exports = config;
