'use strict';
const api = require('./api');
const http = require('./request');
const { sha256Hex } = require('./sha256');

// Reuse existing tickets, private content endpoint, hash/scanning and download lifecycle.
function uploadAvatar(filePath) {
  const token = http.getToken();
  const current = () => { if (!token || http.getToken() !== token) throw Error('登录身份已变化，请重新选择头像'); };
  return new Promise((resolve, reject) => {
    wx.getFileSystemManager().readFile({ filePath, success: r => resolve(r.data), fail: () => reject(Error('头像读取失败，请重新选择')) });
  }).then(data => {
    current();
    const bytes = new Uint8Array(data);
    if (!bytes.length || bytes.length > 2 * 1024 * 1024) throw Error('头像需小于或等于2MB');
    const png = bytes[0] === 137 && bytes[1] === 80 && bytes[2] === 78 && bytes[3] === 71;
    const jpeg = bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255;
    if (!png && !jpeg) throw Error('头像仅支持JPG或PNG图片');
    const mime = png ? 'image/png' : 'image/jpeg';
    return api.getUploadTicket('USER_AVATAR', mime, bytes.length, sha256Hex(data)).then(ticket => {
      current();
      return new Promise((resolve, reject) => {
        const success = res => {
          if (res.statusCode < 200 || res.statusCode >= 300) { reject(Error('头像上传失败，请重试')); return; }
          if (/^local:\/\//.test(ticket.uploadUrl)) {
            let body;
            try { body = JSON.parse(res.data); } catch (e) { reject(Error('头像上传响应无效')); return; }
            if (body.code !== 0 || body.data !== true) { reject(Error(body.msg || '头像上传失败')); return; }
          }
          resolve();
        };
        if (/^https:\/\//.test(ticket.uploadUrl)) {
          // A signed object PUT must not receive our application Bearer token.
          wx.request({ url: ticket.uploadUrl, method: 'PUT', data, header: { 'Content-Type': mime }, success,
            fail: () => reject(Error('头像上传失败，请检查网络')) });
        } else if (/^local:\/\//.test(ticket.uploadUrl)) {
          wx.uploadFile({ url: http.config.apiBase + '/app-api/design/v1/assets/' + ticket.assetId + '/content',
            filePath, name: 'file', header: { Authorization: 'Bearer ' + token, 'tenant-id': String(http.config.tenantId) },
            success, fail: () => reject(Error('头像上传失败，请检查网络')) });
        } else reject(Error('头像上传地址不可用'));
      }).then(() => { current(); return api.completeUpload(ticket.assetId); }).then(accepted => {
        current();
        if (accepted !== true) throw Error('头像未通过安全校验，请更换图片');
        return ticket.assetId;
      });
    });
  });
}
module.exports = { uploadAvatar };
