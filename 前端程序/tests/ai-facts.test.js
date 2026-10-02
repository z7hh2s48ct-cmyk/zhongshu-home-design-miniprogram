const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const view = require('../miniprogram/utils/record-view');

function page(file, api, price, shared) {
  const attempts = shared ? shared.attempts : require('./helpers/generation-attempt')();
  let definition; const state = shared ? shared.state : { token: 'A', calls: [], global: { jobId: '71', projectId: '91' }, timers: [] };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/pages/ai-design', file), 'utf8'), {
    require(name) { return {
      '../../utils/access': { protectedPage: value => { definition = value; } }, '../../utils/api': api,
      '../../utils/request': { getToken: () => state.token, isSameSession: token => token === state.token },
      '../../utils/record-view': view, '../../utils/assets': { fetchProfileAvatar: async id => 'asset://' + id,
      fetchAssetDataUrl: async id => state.assetLoader ? state.assetLoader(id) : 'data:image/png;base64,' + id },
      '../../utils/generation-price': price, '../../utils/generation-attempt': attempts,
      '../../utils/generation-options': require('../miniprogram/utils/generation-options')
    }[name]; }, getApp: () => ({ globalData: state.global }),
    wx: Object.fromEntries(['redirectTo', 'navigateBack', 'navigateTo', 'showToast', 'showModal'].map(key => [key, value => state.calls.push([key, value])])),
    setTimeout: callback => { state.timers.push(callback); return state.timers.length; }, clearTimeout() {}, setInterval: () => 0, clearInterval() {}
  });
  return { state, api, attempts, page: { ...definition, data: structuredClone(definition.data), setData(patch) {
    // 与真机 setData 对齐：支持 'a[0].b' 路径键
    for (const [key, value] of Object.entries(patch)) {
      if (!/[\[.]/.test(key)) { this.data[key] = value; continue; }
      const keys = key.replace(/\]/g, '').replace(/\[/g, '.').split('.');
      let target = this.data;
      for (let i = 0; i < keys.length - 1; i++) target = target[keys[i]];
      target[keys[keys.length - 1]] = value;
    }
  } } };
}
const tick = () => new Promise(resolve => setImmediate(resolve));

test('生成页面显示服务端阶段与数量；已有一张结果仍等待结算', async () => {
  const e = page('generating.js', { getAiJob: async () => ({ jobId: '71', projectId: '91', phase: 'ELEVATION', status: 'RUNNING', requestedCount: 4, acceptedCount: 1, progress: 23, allowedActions: ['CANCEL'] }),
    getProject: async (projectId, jobId) => ({ candidates: [{ candidateId: 'c1', jobId: '71', assetId: 'a1', slotNo: 1 }] }) });
  e.page.onLoad({ stage: 'plane', count: '2' }); await tick();
  assert.equal(e.page.data.stage, 'elevation'); assert.equal(e.page.data.count, 4); assert.equal(e.page.data.acceptedCount, 1);
  assert.equal(e.page.data.finished, false);
  assert.equal(e.page.data.candidates.length, 1); assert.equal(e.page.data.candidates[0].url, 'data:image/png;base64,a1');
  assert.equal(e.page.data.pendingSlots, 3); assert.equal(e.state.calls.length, 0); e.page.onUnload();
});
test('生成页从服务端任务冻结值恢复4K横屏，重新生成沿用原规格', async () => {
  const requests = [];
  const e = page('generating.js', {
    getAiJob: async () => ({ jobId: '71', projectId: '91', phase: 'FLAT', status: 'SUCCEEDED', requestedCount: 2, acceptedCount: 1, progress: 100, allowedActions: [] }),
    getProject: async () => ({ resolution: '4K', orientation: 'LANDSCAPE', candidates: [{ candidateId: 'c1', jobId: '71', assetId: 'a1', slotNo: 1 }] }),
    createFlatJob: async (...args) => { requests.push(args); return { jobId: '72' }; }
  }, { confirm: async (stage, count, description, options) => {
    assert.equal(options.resolution, '4K');
    return { ruleId: '4k', ruleVersion: 1 };
  } });
  e.page.onLoad({ stage: 'plane', count: '2' }); await tick();
  assert.equal(e.page.data.resolution, '4K'); assert.equal(e.page.data.orientation, 'LANDSCAPE');
  await e.page.regenerate();
  assert.equal(requests[0][4].resolution, '4K'); assert.equal(requests[0][4].orientation, 'LANDSCAPE');
  e.page.onUnload();
});
test('生成轮询迟到响应不能跨账号写入或跳转', async () => {
  let resolve; const e = page('generating.js', { getAiJob: () => new Promise(r => { resolve = r; }) });
  e.page.onLoad({}); e.state.token = 'B'; resolve({ jobId: '71', status: 'SUCCEEDED', projectId: '91', requestedCount: 1 }); await tick();
  assert.equal(e.page.data.finished, false); assert.equal(e.state.calls.length, 0); e.page.onUnload();
});
test('生成完成留在本页：候选内联呈现，页内选定平面后出现立面引导', async () => {
  const e = page('generating.js', {
    getAiJob: async () => ({ jobId: '71', projectId: '91', phase: 'FLAT', status: 'SUCCEEDED', requestedCount: 2, acceptedCount: 2, progress: 100, allowedActions: [] }),
    getProject: async (projectId, jobId) => ({ candidates: [
      { candidateId: 'cd1', jobId: '71', assetId: 'a1', slotNo: 1 },
      { candidateId: 'cd2', jobId: '71', assetId: 'a2', slotNo: 2 }] }),
    selectFlat: async (projectId, jobId, candidateId) => { assert.equal(candidateId, 'cd2'); return {}; }
  });
  e.page.onLoad({ stage: 'plane', count: '2' }); await tick();
  assert.equal(e.page.data.finished, true);
  assert.equal(e.page.data.candidates.length, 2); assert.equal(e.page.data.pendingSlots, 0);
  assert.deepEqual(e.state.calls.filter(c => c[0] === 'redirectTo'), [], '完成不应跳转选择页');
  assert.equal(e.page.data.nextLabel, '');
  await e.page.selectCandidate({ currentTarget: { dataset: { id: 'cd2' } } });
  assert.equal(e.page.data.selectedId, 'cd2'); assert.equal(e.page.data.selectDone, true);
  assert.equal(e.page.data.nextLabel, '下一步：配置立面生成');
  assert.equal(e.state.global.selectedFlatAssetId, 'a2');
  e.page.nextStep();
  assert.equal(e.state.calls.at(-1)[0], 'redirectTo'); assert.equal(e.state.calls.at(-1)[1].url, '/pages/ai-design/elevation-setup');
  e.page.onUnload();
});
test('立面完成页内选定后引导查看最终方案', async () => {
  const e = page('generating.js', {
    getAiJob: async () => ({ jobId: '71', projectId: '91', phase: 'ELEVATION', status: 'SUCCEEDED', requestedCount: 1, acceptedCount: 1, progress: 100, allowedActions: [] }),
    getProject: async () => ({ candidates: [{ candidateId: 'e1', jobId: '71', assetId: 'ae1', slotNo: 1 }] }),
    selectElevation: async () => ({ resultVersionId: 'v9' })
  });
  e.page.onLoad({ stage: 'elevation', count: '1' }); await tick();
  await e.page.selectCandidate({ currentTarget: { dataset: { id: 'e1' } } });
  assert.equal(e.page.data.nextLabel, '查看最终方案');
  assert.equal(e.state.global.resultVersionId, 'v9');
  e.page.nextStep();
  // 直达结果页必须带 projectId/resultVersionId，不依赖 globalData（场景恢复后内存态可能为空）
  assert.equal(e.state.calls.at(-1)[0], 'redirectTo'); assert.equal(e.state.calls.at(-1)[1].url, '/pages/ai-design/result?projectId=91&resultVersionId=v9');
  e.page.onUnload();
});
test('重新生成先确认准确报价，失败重试保持请求与幂等键；取消不建单', async () => {
  const requests = []; let fail = true, confirm = true;
  const e = page('result.js', { getResultVersions: async () => ({ list: [{ versionId: '19', version: 1, superseded: false }] }),
    getProject: async () => ({ resolution: '4K', orientation: 'PORTRAIT' }),
    createRevisionRequest: async (...args) => { requests.push(args); if (fail) throw Error('network'); return { jobId: '72' }; }
  }, { confirm: async (stage, count, description, options) => { assert.equal(stage, 'ELEVATION'); assert.equal(count, 2); assert.match(description, /原方案保留/);
    assert.equal(options.resolution, '4K');
    if (!confirm) throw { cancelled: true }; return { ruleId: '123', ruleVersion: 2 }; } });
  e.page.onLoad({ projectId: '91' }); await tick(); await tick();
  assert.equal(e.page.data.optionsReady, true);
  await e.page.regenerate(); fail = false; await e.page.regenerate();
  assert.deepEqual(requests[0], requests[1]); assert.equal(requests[0][1].priceConfirmation.ruleVersion, 2);
  assert.equal(requests[0][1].resolution, '4K'); assert.equal(requests[0][1].orientation, 'PORTRAIT');
  confirm = false; await e.page.regenerate(); assert.equal(requests.length, 2);
});

test('结果页规格读取失败时不按默认2K重生，重试成功后才开放', async () => {
  let fail = true, revisions = 0;
  const e = page('result.js', {
    getResultVersions: async () => ({ list: [{ versionId: '19', version: 1, superseded: false }] }),
    getProject: async () => { if (fail) throw Error('network'); return { resolution: '4K', orientation: 'LANDSCAPE' }; },
    createRevisionRequest: async () => { revisions++; return { jobId: '72' }; }
  }, { confirm: async () => ({ ruleId: '4k', ruleVersion: 1 }) });
  e.page.onLoad({ projectId: '91' }); await tick(); await tick();
  assert.equal(e.page.data.optionsError, true); await e.page.regenerate(); assert.equal(revisions, 0);
  fail = false; await e.page.loadGenerationOptions();
  assert.equal(e.page.data.optionsReady, true); assert.equal(e.page.data.resolution, '4K');
  await e.page.regenerate(); assert.equal(revisions, 1);
});

test('explicit revision price rejection keeps operation key but reconfirms v2 rather than replaying rejected v1', async () => {
  const requests=[];let version=1;
  const e=page('result.js',{getResultVersions:async()=>({list:[{versionId:'19',version:1,superseded:false}]}),
    getProject:async()=>({resolution:'4K',orientation:'PORTRAIT'}),
    createRevisionRequest:async(...args)=>{requests.push(args);if(requests.length===1)throw {code:1072000001};return {jobId:'72'};}},
    {confirm:async()=>({ruleId:'fixture',ruleVersion:version})});
  e.page.onLoad({projectId:'91'});await tick();await tick();await e.page.regenerate();version=2;await e.page.regenerate();
  assert.equal(requests[0][2],requests[1][2]);assert.equal(requests[0][1].priceConfirmation.ruleVersion,1);assert.equal(requests[1][1].priceConfirmation.ruleVersion,2);
});

test('ambiguous elevation reload displays frozen MODERN/2K/four candidates and replays same operation', async () => {
  const requests=[];let fail=true;const api={getPointAccount:async()=>({availablePoints:1000}),createElevationJob:async(...args)=>{requests.push(args);if(fail)throw Error('lost response');return{jobId:'72'};}};
  const price={refresh() {},confirm:async()=>({ruleId:'fixture',ruleVersion:1})};
  let e=page('elevation-setup.js',api,price);e.page.setData({quoteReady:true,style:'MODERN',roof:'FLAT_ROOF',wall:'GREY_STONE',accent:'BLACK',count:4,resolution:'2K',orientation:'PORTRAIT'});
  await e.page.generate();e=page('elevation-setup.js',api,price,e);e.page.onShow();
  assert.equal(e.page.data.style,'MODERN');assert.equal(e.page.data.resolution,'2K');assert.equal(e.page.data.count,4);assert.equal(e.page.data.roof,'FLAT_ROOF');
  e.page.setData({quoteReady:true});fail=false;await e.page.generate();
  assert.deepEqual(JSON.parse(JSON.stringify(requests[0])),JSON.parse(JSON.stringify(requests[1])));
});

test('candidate ticket and decode failures retry original asset without another paid job',async()=>{
  let reads=0;const e=page('generating.js',{getAiJob:async()=>({jobId:'71',projectId:'91',phase:'FLAT',status:'SUCCEEDED',requestedCount:1,acceptedCount:1}),
    getProject:async()=>({candidates:[{candidateId:'c1',jobId:'71',assetId:'a1',slotNo:1}]})});
  e.state.assetLoader=async id=>{assert.equal(id,'a1');reads++;if(reads===1)throw Error('expired ticket');return'original-asset';};
  e.page.onLoad({});await tick();await tick();assert.match(e.page.data.candidates[0].imageError,/重试/);
  await e.page.retryCandidateImage({currentTarget:{dataset:{id:'c1'}}});assert.equal(e.page.data.candidates[0].url,'original-asset');
  e.page.candidateImageError({currentTarget:{dataset:{id:'c1'}}});assert.equal(e.page.data.candidates[0].url,'');
  await e.page.retryCandidateImage({currentTarget:{dataset:{id:'c1'}}});assert.equal(reads,3);assert.equal(e.page.data.candidates[0].url,'original-asset');e.page.onUnload();
});
