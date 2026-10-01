const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
module.exports = function loadAttempt(storage = new Map(), session = { scope: 'fixture-scope' }) {
  const module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../miniprogram/utils/generation-attempt.js'), 'utf8'), {
    module, require(name) {
      if (name === './budget-draft') return { sessionScope: () => session.scope };
      if (name === './sha256') return require('../../miniprogram/utils/sha256');
      throw Error(name);
    }, wx: { getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, structuredClone(value)), removeStorageSync: key => storage.delete(key) }
  });
  return module.exports;
};
