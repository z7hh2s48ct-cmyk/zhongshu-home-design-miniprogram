const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const loadAttempt = require('./helpers/generation-attempt');
const tick = () => new Promise(resolve => setImmediate(resolve));
function page(api, shared = {}) {
  shared.storage ||= new Map(); shared.session ||= { scope: 'fixture-session' }; shared.global ||= {}; shared.calls ||= []; shared.quotes ||= [];
  const attempts = loadAttempt(shared.storage, shared.session); let definition;
  const dependencies = {
    '../../utils/access': { protectedPage: value => { definition = value; } }, '../../utils/api': api,
    '../../utils/request': { getToken: () => 'fixture' }, '../../utils/config': {},
    '../../utils/sha256': require('../miniprogram/utils/sha256'), '../../utils/design-inputs': require('../miniprogram/utils/design-inputs'),
    '../../utils/generation-options': require('../miniprogram/utils/generation-options'), '../../utils/generation-attempt': attempts,
    '../../utils/generation-price': { refresh() {}, confirm: async (...args) => { shared.quotes.push(args); if (shared.cancelQuote) throw { cancelled: true }; if (shared.quoteFailure) throw {msg:'quote fetch failed'}; return { ruleId: 'old-rule', ruleVersion: shared.quoteVersion || 1 }; } }
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/pages/ai-design/index.js'), 'utf8'), {
    require: name => { if (!dependencies[name]) throw Error(name); return dependencies[name]; }, getApp: () => ({ globalData: shared.global }),
    wx: { showToast: value => shared.calls.push(['toast', value]), redirectTo: value => shared.calls.push(['redirect', value]) }
  });
  const instance = { ...definition, data: structuredClone(definition.data), setData: function(patch) { Object.assign(this.data, patch); } };
  instance.onLoad({}); instance.setData({ quoteReady: true });
  return { page: instance, shared, attempts };
}
test('lost project response, duplicate tap and page reentry reuse project/job keys and original quote', async () => {
  const projects = []; const jobs = []; let firstProject = true; let firstJob = true;
  const api = {
    createProject: async (body, key) => { projects.push({ body, key }); if (firstProject) { firstProject = false; throw { msg: 'response lost' }; } return { projectId: '91' }; },
    createFlatJob: async (...args) => { jobs.push(args); if (firstJob) { firstJob = false; throw { msg: 'job response lost' }; } return { jobId: '71' }; }
  };
  let e = page(api); e.page.setData({ mode: 0, faceWidth: '18', depth: '20', floorIndex: 3, note: '原需求' });
  const pending = e.page.generate(); assert.equal(e.page.generate(), undefined); await pending;
  e = page(api, e.shared); assert.equal(e.page.data.mode, 0); assert.equal(e.page.data.faceWidth, '18'); await e.page.generate();
  e = page(api, e.shared); await e.page.generate();
  assert.equal(projects.length, 2); assert.equal(projects[0].key, projects[1].key);
  assert.equal(jobs.length, 2); assert.equal(jobs[0][2], jobs[1][2]); assert.equal(jobs[0][2], projects[0].key);
  assert.equal(e.shared.quotes.length, 1); assert.equal(e.shared.global.jobId, '71'); assert.equal(e.attempts.read('initial-flat'), null);
});
test('autonomous mode ignores hidden reference, retains sketch and prompt; case mode uses reference only', async () => {
  for (const mode of [0, 1]) {
    const bodies = []; const e = page({ createProject: async body => { bodies.push(body); return { projectId: '91' }; }, createFlatJob: async () => ({ jobId: '71' }) });
    e.page.setData({ mode, refCase: { caseId: 'old-case' }, sketchAssetId: 'new-sketch', prompt: '自主需求', faceWidth: '12', depth: '14' });
    await e.page.generate(); const body = bodies[0];
    assert.equal(body.sourceType, mode === 0 ? 'CASE_REFERENCE' : 'SELF_UPLOAD');
    assert.equal(body.refCaseId, mode === 0 ? 'old-case' : undefined);
    assert.equal(body.sketchAssetId, mode === 1 ? 'new-sketch' : undefined);
    if (mode === 1) assert.equal(body.requirementInputs.prompt, '自主需求');
  }
});
test('different pending intent/409 does not create another paid job or replace idempotency key', async () => {
  const calls = []; const e = page({ createProject: async (body, key) => { calls.push(key); throw { code: 409, msg: '请求内容冲突' }; } });
  e.page.setData({ mode: 1, prompt: '原意图' }); await e.page.generate(); const key = e.attempts.read('initial-flat').key;
  e.page.setData({ prompt: '新意图' }); await e.page.generate(); assert.equal(calls.length, 1); assert.equal(e.attempts.read('initial-flat').key, key);
  assert.equal(e.shared.calls.filter(item => item[0] === 'redirect').length, 0);
});
test('account switch during project response prevents job creation, globals and navigation writes', async () => {
  let resolve; const jobs = []; const e = page({ createProject: () => new Promise(r => { resolve = r; }), createFlatJob: async (...args) => jobs.push(args) });
  e.page.setData({ mode: 1, prompt: 'original' }); const pending = e.page.generate(); await tick();
  e.shared.session.scope = 'new-account'; e.shared.global = {}; resolve({ projectId: 'old-account-project' }); await pending;
  assert.equal(jobs.length, 0); assert.equal(e.shared.global.projectId, undefined); assert.equal(e.shared.calls.filter(item => item[0] === 'redirect').length, 0);
});

test('initial page and my-project resume share one intent; returning pending page cannot submit a second job', async () => {
  const jobs=[]; let first=true;
  const api={createProject:async()=>({projectId:'91'}),createFlatJob:async(...args)=>{jobs.push(args);if(first){first=false;throw {msg:'first send never arrived'};}return {jobId:'71'};},
    getProject:async()=>({projectId:'91',resumeAction:'CREATE_FLAT_JOB',requestedCount:2,resolution:'4K',orientation:'PORTRAIT'})};
  const e=page(api);e.page.setData({mode:1,prompt:'original'});await e.page.generate();
  const module={exports:{}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../miniprogram/utils/project-resume.js'),'utf8'),{
    module,require:name=>({'./api':api,'./record-view':require('../miniprogram/utils/record-view'),'./generation-options':require('../miniprogram/utils/generation-options'),
      './generation-attempt':e.attempts,'./request':{captureSession:()=>e.shared.session.scope,isSameSession:s=>s===e.shared.session.scope},'./generation-price':{confirm:async()=>{throw Error('must reuse original quote');}}})[name],
    getApp:()=>({globalData:e.shared.global}),wx:{navigateTo() {}}
  });
  await module.exports.resume('91');assert.equal(jobs[0][2],jobs[1][2]);assert.equal(e.attempts.read('initial-flat'),null);
  await e.page.generate();assert.equal(jobs.length,2);assert.equal(e.shared.global.jobId,'71');
});

test('cancel before submission leaves no pending UI/slot and edited dimensions can start normally',async()=>{
  const requests=[];const e=page({createProject:async(body,key)=>{requests.push({body,key});return{projectId:'91'};},createFlatJob:async()=>({jobId:'71'})});
  e.shared.cancelQuote=true;e.page.setData({mode:0,faceWidth:'12',depth:'14'});await e.page.generate();
  assert.equal(e.attempts.read('initial-flat'),null);assert.equal(e.page.data.pendingGeneration,false);
  e.shared.cancelQuote=false;e.page.setData({faceWidth:'18'});await e.page.generate();assert.equal(requests.length,1);assert.equal(requests[0].body.requirementInputs.faceWidthM,18);
});

test('same case onShow preserves edited dimensions; a new case imports its own dimensions once',async()=>{
  const e=page({getPointAccount:async()=>({availablePoints:1000})});e.shared.global.refCase={caseId:'first',faceWidth:12,depth:14,floorCount:2};e.page.onShow();
  e.page.setData({faceWidth:'18',depth:'20',floorIndex:3});e.page.onShow();await tick();
  assert.equal(e.page.data.faceWidth,'18');assert.equal(e.page.data.depth,'20');assert.equal(e.page.data.floorIndex,3);
  e.shared.global.refCase={caseId:'second',faceWidth:8,depth:10,floorCount:1};e.page.onShow();assert.equal(e.page.data.faceWidth,'8');assert.equal(e.page.data.floorIndex,0);
});

function resumeFixture(api, e, confirm) {
  const module={exports:{}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../miniprogram/utils/project-resume.js'),'utf8'),{
    module,require:name=>({'./api':api,'./record-view':require('../miniprogram/utils/record-view'),
      './generation-options':require('../miniprogram/utils/generation-options'),'./generation-attempt':e.attempts,
      './request':{captureSession:()=>e.shared.session.scope,isSameSession:s=>s===e.shared.session.scope},
      './generation-price':{confirm}})[name],getApp:()=>({globalData:e.shared.global}),
    wx:{navigateTo:value=>e.shared.calls.push(['navigate',value])}
  });
  return module.exports;
}
test('resume definite price rejection re-confirms v2 using the original initial operation key',async()=>{
  const submissions=[];let rejecting=true;
  const api={getProject:async()=>({projectId:'91',resumeAction:'CREATE_FLAT_JOB'}),createFlatJob:async(...args)=>{
    submissions.push(args);if(rejecting){rejecting=false;throw {code:1072000001};}return {jobId:'71'};
  }};
  const e=page(api);let a=e.attempts.begin('initial-flat',{count:4,imageOptions:{resolution:'2K',orientation:'LANDSCAPE'}});
  a.projectId='91';a.confirmedPrice={ruleVersion:1};e.attempts.save('initial-flat',a);
  let quotes=0;const resume=resumeFixture(api,e,async()=>{quotes++;return {ruleVersion:2};});
  await assert.rejects(resume.resume('91'));a=e.attempts.read('initial-flat');assert.equal(a.confirmedPrice,undefined);
  await resume.resume('91');assert.equal(quotes,1);assert.equal(submissions[0][2],submissions[1][2]);
  assert.equal(submissions[1][1],4);assert.equal(submissions[1][3].ruleVersion,2);assert.equal(submissions[1][4].resolution,'2K');
});
test('resume ambiguous delivery retains its old quote/key and cancelled unsubmitted quote clears only its draft',async()=>{
  const api={getProject:async()=>({projectId:'91',resumeAction:'CREATE_FLAT_JOB'}),createFlatJob:async()=>{throw {msg:'transport timeout'};}};
  const e=page(api);const a=e.attempts.begin('initial-flat',{count:2,imageOptions:{resolution:'4K',orientation:'PORTRAIT'}});
  a.projectId='91';a.confirmedPrice={ruleVersion:1};e.attempts.save('initial-flat',a);
  await assert.rejects(resumeFixture(api,e,async()=>{throw Error('must not requote');}).resume('91'));
  assert.equal(e.attempts.read('initial-flat').key,a.key);assert.equal(e.attempts.read('initial-flat').confirmedPrice.ruleVersion,1);
  e.attempts.complete('initial-flat',a);await assert.rejects(resumeFixture(api,e,async()=>{throw {cancelled:true};}).resume('91'));
  assert.equal(e.attempts.read('resume-flat:91'),null);
});
test('account change while fetching resume project cannot write old globals or navigate',async()=>{
  let resolve;const api={getProject:()=>new Promise(r=>{resolve=r;})};const e=page(api);
  const promise=resumeFixture(api,e,async()=>({})).resume('91');e.shared.session.scope='new';
  resolve({projectId:'91',jobId:'71',resumeAction:'POLL_JOB'});await assert.rejects(promise);
  assert.equal(e.shared.global.projectId,undefined);assert.equal(e.shared.calls.length,0);
});

test('lost create response is recovered by server creation identity across my-projects and original page',async()=>{
  const projects=[],jobs=[];let first=true;const accepted=new Map();
  const api={createProject:async(body,key)=>{projects.push(key);if(first){first=false;throw {msg:'created, response lost'};}return{projectId:'91'};},
    createFlatJob:async(...args)=>{jobs.push(args);if(!accepted.has(args[2]))accepted.set(args[2],'71');return{jobId:accepted.get(args[2])};},
    getProject:async()=>({projectId:'91',resumeAction:'CREATE_FLAT_JOB',initialGenerationKey:projects[0]})};
  const e=page(api);e.page.setData({mode:1,prompt:'original',count:4,resolution:'2K',orientation:'LANDSCAPE'});await e.page.generate();
  assert.equal(e.attempts.read('initial-flat').projectId,undefined);
  await resumeFixture(api,e,async()=>{throw Error('keep original quote');}).resume('91');
  await e.page.generate();assert.equal(jobs.length,1);assert.equal(jobs[0][2],projects[0]);assert.equal(jobs[0][1],4);
  assert.equal(accepted.size,1);
});

test('cached tab observes completed shared slot onShow and permits a deliberate new design',async()=>{
  const jobs=[];const api={getPointAccount:async()=>({availablePoints:1000}),createProject:async()=>({projectId:'92'}),createFlatJob:async(...args)=>{jobs.push(args);return{jobId:'72'};}};
  const e=page(api);const a=e.attempts.begin('initial-flat',{count:2});e.page._lastAttemptKey=a.key;e.page._recoveringAttempt=true;
  e.attempts.complete('initial-flat',a);e.page.onShow();e.page.setData({mode:1,prompt:'new design',quoteReady:true});await e.page.generate();
  assert.equal(jobs.length,1);assert.notEqual(jobs[0][2],a.key);assert.equal(e.page.data.pendingGeneration,false);
});

test('quote fetch failure before any write clears draft so edited intent can submit',async()=>{
  const projects=[];const e=page({createProject:async(body,key)=>{projects.push(body);return{projectId:'91'};},createFlatJob:async()=>({jobId:'71'})});
  e.shared.quoteFailure=true;e.page.setData({mode:1,prompt:'first'});await e.page.generate();assert.equal(e.attempts.read('initial-flat'),null);
  e.shared.quoteFailure=false;e.page.setData({prompt:'edited'});await e.page.generate();assert.equal(projects.length,1);assert.equal(projects[0].requirementInputs.prompt,'edited');
});
