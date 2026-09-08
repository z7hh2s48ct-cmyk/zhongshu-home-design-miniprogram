'use strict';

/**
 * 纯 JS SHA-256（十六进制摘要）。
 * 用途：上传资产前计算内容指纹，后端按声明 SHA-256 强校验（AssetService.completeUpload）。
 * 小程序环境无原生 crypto.subtle，故内置实现；输入为 ArrayBuffer/Uint8Array。
 */

var K = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
];

function rotr(x, n) { return (x >>> n) | (x << (32 - n)); }

function sha256Hex(data) {
  var bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
  var bitLen = bytes.length * 8;
  // 总长补齐到 64 字节倍数，且给 0x80 + 8 字节长度留位（L ≡ 56 mod 64 时多补一轮）
  var totalLen = (((bytes.length + 8) >> 6) + 1) << 6;
  var buffer = new Uint8Array(totalLen);
  buffer.set(bytes);
  buffer[bytes.length] = 0x80;
  // 消息长度按大端 64 位写入；JS 位运算是 32 位，高 32 位在输入 < 2^29 字节时恒为 0
  var lenPos = totalLen - 8;
  buffer[lenPos + 7] = bitLen & 0xff;
  buffer[lenPos + 6] = (bitLen >>> 8) & 0xff;
  buffer[lenPos + 5] = (bitLen >>> 16) & 0xff;
  buffer[lenPos + 4] = (bitLen >>> 24) & 0xff;

  var h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
  var w = new Array(64);

  for (var offset = 0; offset < totalLen; offset += 64) {
    for (var t = 0; t < 16; t++) {
      var j = offset + t * 4;
      w[t] = (buffer[j] << 24) | (buffer[j + 1] << 16) | (buffer[j + 2] << 8) | buffer[j + 3];
    }
    for (var t2 = 16; t2 < 64; t2++) {
      var s0 = rotr(w[t2 - 15], 7) ^ rotr(w[t2 - 15], 18) ^ (w[t2 - 15] >>> 3);
      var s1 = rotr(w[t2 - 2], 17) ^ rotr(w[t2 - 2], 19) ^ (w[t2 - 2] >>> 10);
      w[t2] = (w[t2 - 16] + s0 + w[t2 - 7] + s1) | 0;
    }
    var a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
    for (var i = 0; i < 64; i++) {
      var S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      var ch = (e & f) ^ (~e & g);
      var temp1 = (hh + S1 + ch + K[i] + w[i]) | 0;
      var S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      var maj = (a & b) ^ (a & c) ^ (b & c);
      var temp2 = (S0 + maj) | 0;
      hh = g; g = f; f = e; e = (d + temp1) | 0;
      d = c; c = b; b = a; a = (temp1 + temp2) | 0;
    }
    h[0] = (h[0] + a) | 0; h[1] = (h[1] + b) | 0; h[2] = (h[2] + c) | 0; h[3] = (h[3] + d) | 0;
    h[4] = (h[4] + e) | 0; h[5] = (h[5] + f) | 0; h[6] = (h[6] + g) | 0; h[7] = (h[7] + hh) | 0;
  }

  var hex = '';
  for (var n = 0; n < 8; n++) {
    hex += (h[n] >>> 0).toString(16).padStart(8, '0');
  }
  return hex;
}

module.exports = { sha256Hex: sha256Hex };
