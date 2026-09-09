'use strict';

/** 环境配置：开发期指向本地后端；上线换正式域名 */
const config = {
  apiBase: 'http://localhost:48080',
  env: 'dev',
  tenantId: 1
};

module.exports = config;
