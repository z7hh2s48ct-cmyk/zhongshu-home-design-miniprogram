'use strict';

/**
 * 资产图片加载。
 * 本地存储适配器产生的 local:// 地址无法被 <image> 组件加载（COS 凭据到位前的过渡态），
 * 走「下载票据 → 开发期内容端点(arraybuffer) → base64 data URL」链路；
 * 切 COS 后后端直接下发签名 URL，本模块的取图入口不变。
 * <image> 不能携带 Authorization 头，故内容端点的会话头在 wx.request 中显式注入。
 */

var api = require('./api');
var http = require('./request');
var config = require('./config');

var cache = {};    // assetId -> data URL（进程内缓存，避免重复消耗票据）
var pending = {};  // assetId -> Promise（并发去重）

function fetchAssetDataUrl(assetId) {
  if (!assetId) return Promise.reject({ msg: '缺少资产编号' });
  if (cache[assetId]) return Promise.resolve(cache[assetId]);
  if (pending[assetId]) return pending[assetId];
  pending[assetId] = api.createDownloadTicket(assetId).then(function (ticket) {
    return new Promise(function (resolve, reject) {
      wx.request({
        url: config.apiBase + '/app-api/design/v1/assets/' + assetId + '/content?ticket='
          + encodeURIComponent(ticket.ticketId || ''),
        responseType: 'arraybuffer',
        header: { 'Authorization': 'Bearer ' + http.getToken() },
        success: function (res) {
          if (res.statusCode >= 400) { reject({ msg: '资产读取失败(' + res.statusCode + ')' }); return; }
          var header = res.header || {};
          var mime = header['Content-Type'] || header['content-type'] || 'image/jpeg';
          var dataUrl = 'data:' + mime + ';base64,' + wx.arrayBufferToBase64(res.data);
          cache[assetId] = dataUrl;
          resolve(dataUrl);
        },
        fail: function () { reject({ msg: '资产读取失败' }); }
      });
    });
  }).then(
    function (url) { delete pending[assetId]; return url; },
    function (err) { delete pending[assetId]; throw err; }
  );
  return pending[assetId];
}

/** 已取到过的图直接同步返回（同步模板兜底场景） */
function cachedAssetDataUrl(assetId) {
  return assetId ? (cache[assetId] || '') : '';
}

module.exports = { fetchAssetDataUrl: fetchAssetDataUrl, cachedAssetDataUrl: cachedAssetDataUrl };
