'use strict';

/** 展示格式化：编码→中文标签、时间与金额。后端下发编码，客户端负责展示文案。 */

var STYLE_LABELS = {
  NEW_CHINESE: '新中式',
  MODERN: '现代',
  CHINESE: '中式',
  EUROPEAN: '欧式'
};

var STRUCTURE_LABELS = {
  BRICK: '砖混结构',
  FRAME: '框架结构',
  STEEL: '钢结构'
};

function styleLabel(code) { return STYLE_LABELS[code] || code || ''; }

function structureLabel(code) { return STRUCTURE_LABELS[code] || code || ''; }

/** 后端 sentAt → 「09-06 10:00」；兼容 epoch 毫秒数字与 ISO 字符串（全局 Jackson 把 LocalDateTime 序列化为毫秒），无效输入返回空串 */
function shortTime(value) {
  if (value == null || value === '') return '';
  var raw = typeof value === 'number' ? value
    : (/^\d+$/.test(String(value).trim()) ? Number(String(value).trim()) : String(value).replace(' ', 'T'));
  var date = new Date(raw);
  if (isNaN(date.getTime())) return '';
  function pad(n) { return n < 10 ? '0' + n : '' + n; }
  return pad(date.getMonth() + 1) + '-' + pad(date.getDate()) + ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes());
}

/** 分 → 「x.x万」；区间展示用 */
function centsToWan(cents) {
  if (typeof cents !== 'number' || isNaN(cents)) return '—';
  var wan = cents / 1000000;
  return (wan % 1 === 0 ? wan : wan.toFixed(1)) + '万';
}

module.exports = { styleLabel: styleLabel, structureLabel: structureLabel, shortTime: shortTime, centsToWan: centsToWan };
