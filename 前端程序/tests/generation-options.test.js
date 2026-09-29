const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const mini = path.resolve(__dirname, '../miniprogram');

function loadModule(relative, dependencies, globals) {
  const module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(path.join(mini, relative), 'utf8'), Object.assign({
    module,
    require(name) {
      if (!(name in dependencies)) throw Error('Unexpected dependency ' + name);
      return dependencies[name];
    }
  }, globals || {}));
  return module.exports;
}

function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}

function loadCreatePage(file, price) {
  let definition;
  const calls = [];
  const globalData = { projectId: '91', selectedFlatAssetId: 'flat-1' };
  const api = {
    getPointAccount: async () => ({ availablePoints: 100 }),
    createProject: async body => { calls.push(['project', body]); return { projectId: '91' }; },
    createFlatJob: async (...args) => { calls.push(['flat', ...args]); return { jobId: '71' }; },
    createElevationJob: async (...args) => { calls.push(['elevation', ...args]); return { jobId: '72' }; }
  };
  const generationOptions = require('../miniprogram/utils/generation-options');
  const dependencies = {
    '../../utils/access': { protectedPage: value => { definition = value; } },
    '../../utils/api': api,
    '../../utils/request': {},
    '../../utils/config': {},
    '../../utils/sha256': {},
    '../../utils/design-inputs': {
      floorLabels: () => [], familyLabels: () => [], indexOfFloor: () => 0, indexOfFamily: () => 0,
      buildRequirementInputs: () => ({ inputs: { floorCount: 2 } })
    },
    '../../utils/generation-options': generationOptions,
    '../../utils/generation-price': price,
    '../../utils/assets': { fetchAssetDataUrl: async () => 'asset://flat-1' }
  };
  vm.runInNewContext(fs.readFileSync(path.join(mini, 'pages/ai-design', file), 'utf8'), {
    require(name) {
      if (!(name in dependencies)) throw Error('Unexpected dependency ' + name);
      return dependencies[name];
    },
    getApp: () => ({ globalData }),
    wx: Object.fromEntries(['redirectTo', 'navigateTo', 'switchTab', 'showToast'].map(name => [name, value => calls.push([name, value])]))
  });
  const page = { ...definition, data: structuredClone(definition.data), setData(patch) { Object.assign(this.data, patch); } };
  return { page, calls, globalData };
}

test('规格映射给出横竖准确像素，非法历史值回落到阶段默认值', () => {
  const options = require('../miniprogram/utils/generation-options');
  assert.equal(options.selection('FLAT', { resolution: '4K', orientation: 'PORTRAIT' }).outputPixels, '2160 × 3840');
  assert.equal(options.selection('ELEVATION', { resolution: '4K', orientation: 'LANDSCAPE' }).outputPixels, '3840 × 2160');
  assert.deepEqual(options.selection('FLAT', { resolution: '8K', orientation: 'SQUARE' }), {
    resolution: '2K', orientation: 'PORTRAIT', outputPixels: '1152 × 2048'
  });
});

test('报价响应必须与请求档位一致，错误后保持生成禁用并允许重试', async () => {
  let valid = false;
  const price = loadModule('utils/generation-price.js', {
    './api': { getGenerationQuote: async (stage, count, resolution) => ({
      stage, count, resolution: valid ? resolution : '2K', totalPointCost: 80, unitPointCost: 40,
      ruleId: '1', ruleVersion: 1
    }) }
  });
  const page = { data: { count: 2, resolution: '4K' }, setData(patch) { Object.assign(this.data, patch); } };
  await price.refresh(page, 'FLAT');
  assert.equal(page.data.quoteReady, false);
  assert.match(page.data.quoteError, /报价/);
  valid = true;
  await price.refresh(page, 'FLAT');
  assert.equal(page.data.quoteReady, true);
  assert.equal(page.data.quoteError, '');
  assert.match(page.data.priceText, /80/);
});

test('平面生成提交点击时冻结的4K横屏，确认期间改选不改变任务规格', async () => {
  const confirmation = deferred();
  let quoted;
  const runtime = loadCreatePage('index.js', {
    refresh: async () => {},
    confirm: async (stage, count, description, options) => { quoted = { stage, count, options }; return confirmation.promise; }
  });
  Object.assign(runtime.page.data, { resolution: '4K', orientation: 'LANDSCAPE', quoteReady: true });
  const pending = runtime.page.generate();
  Object.assign(runtime.page.data, { resolution: '2K', orientation: 'PORTRAIT' });
  confirmation.resolve({ ruleId: '4k-rule', ruleVersion: 3 });
  await pending;
  assert.equal(quoted.options.resolution, '4K');
  const options = runtime.calls.find(call => call[0] === 'flat')[5];
  assert.equal(options.resolution, '4K'); assert.equal(options.orientation, 'LANDSCAPE');
});

test('立面生成提交点击时冻结的4K竖屏，确认期间改选不改变任务规格', async () => {
  const confirmation = deferred();
  const runtime = loadCreatePage('elevation-setup.js', {
    refresh: async () => {}, confirm: async () => confirmation.promise
  });
  Object.assign(runtime.page.data, { resolution: '4K', orientation: 'PORTRAIT', quoteReady: true });
  const pending = runtime.page.generate();
  Object.assign(runtime.page.data, { resolution: '2K', orientation: 'LANDSCAPE' });
  confirmation.resolve({ ruleId: '4k-rule', ruleVersion: 3 });
  await pending;
  const config = runtime.calls.find(call => call[0] === 'elevation')[2];
  assert.equal(config.resolution, '4K');
  assert.equal(config.orientation, 'PORTRAIT');
});

test('恢复待生成平面任务沿用服务端4K规格，不静默回落2K', async () => {
  const calls = [];
  const globalData = {};
  const api = {
    getProject: async () => ({ projectId: '91', resumeAction: 'CREATE_FLAT_JOB', requestedCount: 2,
      resolution: '4K', orientation: 'LANDSCAPE' }),
    createFlatJob: async (...args) => { calls.push(args); return { jobId: '72' }; }
  };
  const resume = loadModule('utils/project-resume.js', {
    './api': api,
    './record-view': { id: value => String(value || '') || null },
    './generation-options': require('../miniprogram/utils/generation-options'),
    './generation-price': { confirm: async (stage, count, description, options) => {
      assert.equal(options.resolution, '4K'); return { ruleId: '4k', ruleVersion: 1 };
    } }
  }, { getApp: () => ({ globalData }), wx: { navigateTo: () => {} } });
  await resume.resume('91');
  assert.equal(calls[0][4].resolution, '4K'); assert.equal(calls[0][4].orientation, 'LANDSCAPE');
});
