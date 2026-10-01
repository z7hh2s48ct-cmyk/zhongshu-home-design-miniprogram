'use strict';
var api = require('./api');
var http = require('./request');
var config = require('./config');
var cache = {};    // assetId -> data URL
var pending = {};  // assetId -> Promise
var REQUEST_TIMEOUT_MS = 15000;
function readLocalContent(assetId, ticket) {
  return new Promise(function (resolve, reject) {
    wx.request({
      url: config.apiBase + api.assetContentUrl(assetId) + '?ticket='
        + encodeURIComponent(ticket.ticketId || ''),
      responseType: 'arraybuffer',
      timeout: REQUEST_TIMEOUT_MS,
      header: {
        'Authorization': 'Bearer ' + http.getToken(),
        'tenant-id': String(config.tenantId)
      },
      success: function (res) {
        if (res.statusCode < 200 || res.statusCode >= 300) { reject({ msg: '资产读取失败(' + res.statusCode + ')' }); return; }
        var header = res.header || {};
        var mime = header['Content-Type'] || header['content-type'] || '';
        if (!/^image\//i.test(mime)) { reject({ msg: '图片内容不可用，请重试' }); return; }
        var dataUrl = 'data:' + mime + ';base64,' + wx.arrayBufferToBase64(res.data);
        cache[assetId] = dataUrl;
        resolve(dataUrl);
      }, fail: function () { reject({ msg: '资产读取失败' }); }
    });
  });
}
function fetchAssetDataUrl(assetId) {
  if (!assetId) return Promise.reject({ msg: '缺少资产编号' });
  if (cache[assetId]) return Promise.resolve(cache[assetId]);
  if (pending[assetId]) return pending[assetId];
  // HTTPS 签名地址是短期凭据，直接交给 <image>，不能缓存或改走内容端点。
  pending[assetId] = api.createDownloadTicket(assetId)
    .then(function (ticket) {
    return api.resolveDownload(assetId, ticket.ticketId).then(function (result) {
      if (/^https:\/\//.test(result && result.downloadUrl)) return result.downloadUrl;
      if (config.env !== 'dev' || !/^local:\/\//.test(result && result.downloadUrl)) throw Error('图片地址不可用');
      // resolveDownload 会消费首张票据；开发内容端点必须使用重新申请的票据。
      return api.createDownloadTicket(assetId).then(function (freshTicket) { return readLocalContent(assetId, freshTicket); });
    });
    })
    .then(
      function (url) { delete pending[assetId]; return url; },
      function (err) { delete pending[assetId]; throw err; }
    );
  return pending[assetId];
}
function cachedAssetDataUrl(assetId) {
  return assetId ? (cache[assetId] || '') : '';
}

function fetchProfileAvatar(assetId) {
  return fetchAssetDataUrl(assetId);
}

// —— 图片预览（wx.previewImage）——
// 生产环境图片是短期签名 https URL，可直接预览；开发环境 assets.js 给页面的是
// data URL，previewImage 不认，需先 base64 落盘为临时文件。同一 data URL 只落盘一次。
var tempPreviewFiles = {};  // data URL -> 临时文件路径
var tempFileSeq = 0;

function dataUrlExt(mime) {
  var m = /^data:image\/(jpeg|jpg|png|webp|gif)[;,]/.exec(mime || '');
  return m ? (m[1] === 'jpeg' ? 'jpg' : m[1]) : 'jpg';
}

function toPreviewable(url) {
  if (!/^data:/.test(url)) return Promise.resolve(url);
  if (tempPreviewFiles[url]) return Promise.resolve(tempPreviewFiles[url]);
  return new Promise(function (resolve, reject) {
    var marker = url.indexOf(';base64,');
    if (marker < 0) { reject(new Error('图片格式不支持预览')); return; }
    var filePath = wx.env.USER_DATA_PATH + '/preview-' + Date.now() + '-' + (tempFileSeq++) + '.' + dataUrlExt(url);
    wx.getFileSystemManager().writeFile({
      filePath: filePath,
      data: url.slice(marker + ';base64,'.length),
      encoding: 'base64',
      success: function () { tempPreviewFiles[url] = filePath; resolve(filePath); },
      fail: function () { reject(new Error('图片预览准备失败')); }
    });
  });
}

// urls: 图片地址数组（https / data URL 混合均可）；current: 当前图的原始地址（可选）。
function previewImages(urls, current) {
  var list = [].concat(urls || []).filter(function (u) { return !!u; });
  if (!list.length) return;
  Promise.all(list.map(toPreviewable)).then(function (paths) {
    var index = current ? list.indexOf(current) : -1;
    wx.previewImage({ urls: paths, current: index >= 0 ? paths[index] : paths[0] });
  }).catch(function () { /* 预览失败不打断页面 */ });
}

module.exports = {
  fetchAssetDataUrl: fetchAssetDataUrl,
  cachedAssetDataUrl: cachedAssetDataUrl,
  fetchProfileAvatar: fetchProfileAvatar,
  previewImages: previewImages
};
