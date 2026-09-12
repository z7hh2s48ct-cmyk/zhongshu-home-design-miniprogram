const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function harness() {
  const storage = new Map([['zs_access_token', 'old-access'], ['zs_refresh_token', 'old-refresh'], ['v12Authorized', true]]);
  const calls = [], redirects = [], app = { globalData: { projectId: '123' } };
  const context = { module: { exports: {} }, require: () => ({ apiBase: '', tenantId: 1 }),
    getApp: () => app, getCurrentPages: () => [{ route: 'pages/library/index' }],
    wx: { getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value),
      removeStorageSync: key => storage.delete(key), redirectTo: options => redirects.push(options.url),
      request: options => calls.push(options) } };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/utils/request.js'), 'utf8'), context);
  const respond = (index, code, data, statusCode = 200) => calls[index].success({ statusCode, data: { code, data, msg: 'test response' } });
  return { http: context.module.exports, calls, storage, redirects, app, respond };
}
const tick = () => new Promise(resolve => setImmediate(resolve));
const tokens = restricted => ({ accessToken: 'new-access', refreshToken: 'new-refresh', restricted });

test('退出后到达的成功响应不会回填旧用户数据', async () => {
  const h = harness(); const request = h.http.get('/profile');
  h.http.clearTokens(); h.respond(0, 0, { nickname: 'old user' });
  await assert.rejects(request, e => e.code === 'SESSION_CHANGED');
});

test('并发 401 共用一次刷新，原写请求只重放一次并保留幂等键', async () => {
  const h = harness();
  const a = h.http.post('/first', { count: 2 }, { 'Idempotency-Key': 'same-command' });
  const b = h.http.get('/second');
  h.respond(0, 401); h.respond(1, 401, null, 401); await tick();
  assert.equal(h.calls.length, 3);
  assert.match(h.calls[2].url, /token-refresh$/);
  h.respond(2, 0, tokens(false)); await tick();
  assert.equal(h.calls.length, 5);
  assert.equal(h.calls[3].header['Idempotency-Key'], 'same-command');
  assert.equal(h.calls[3].header.Authorization, 'Bearer new-access');
  h.respond(3, 0, 'a'); h.respond(4, 0, 'b');
  assert.deepEqual(await Promise.all([a, b]), ['a', 'b']);
  assert.equal(h.storage.get('zs_refresh_token'), 'new-refresh');
  assert.equal(h.http.isSameSession('old-access'), true);
  h.http.clearTokens();
  assert.equal(h.http.isSameSession('old-access'), false);
});

test('刷新完成后才到达的旧请求 401 不启动第二次刷新', async () => {
  const h = harness(); const a = h.http.get('/a'), b = h.http.get('/b');
  h.respond(0, 401); await tick(); h.respond(2, 0, tokens(false)); await tick();
  h.respond(3, 0, 'a'); await a;
  h.respond(1, 401); await tick();
  assert.equal(h.calls.filter(c => c.url.endsWith('/token-refresh')).length, 1);
  h.respond(4, 0, 'b'); assert.equal(await b, 'b');
});

for (const statusCode of [200, 403]) test(`资源 403 保留令牌和授权状态，HTTP ${statusCode}`, async () => {
  const h = harness(); const request = h.http.get('/other-users-project');
  h.respond(0, 403, null, statusCode);
  await assert.rejects(request, e => e.code === 403);
  assert.equal(h.storage.get('zs_access_token'), 'old-access');
  assert.equal(h.storage.get('v12Authorized'), true);
  assert.equal(h.redirects.length, 0);
});

test('未激活业务码降级授权并保留受限会话用于兑换', async () => {
  const h = harness(); const request = h.http.get('/project'); h.respond(0, 1070001001);
  await assert.rejects(request, e => e.code === 1070001001);
  assert.equal(h.storage.get('zs_access_token'), 'old-access');
  assert.equal(h.storage.get('v12Authorized'), false);
  assert.equal(h.redirects.length, 1);
});

test('刷新仍为受限账号时不会写成已激活', async () => {
  const h = harness(); const request = h.http.get('/access-grant');
  h.respond(0, 401); await tick(); h.respond(1, 0, tokens(true)); await tick();
  h.respond(2, 0, { status: 'NONE' }); await request;
  assert.equal(h.storage.get('v12Authorized'), false);
});

test('刷新令牌无效时清会话且只跳转一次', async () => {
  const h = harness(); const request = h.http.get('/project');
  const rejected = assert.rejects(request, e => e.code === 401);
  h.respond(0, 401); await tick(); h.respond(1, 401); await rejected;
  assert.equal(h.storage.has('zs_access_token'), false);
  assert.equal(h.storage.has('zs_refresh_token'), false);
  assert.equal(Object.keys(h.app.globalData).length, 0);
  assert.equal(h.redirects.length, 1);
});

test('重放后再 401 时结束，不无限刷新', async () => {
  const h = harness(); const request = h.http.get('/project');
  const rejected = assert.rejects(request, e => e.code === 401);
  h.respond(0, 401); await tick(); h.respond(1, 0, tokens(false)); await tick();
  h.respond(2, 401); await rejected;
  assert.equal(h.calls.length, 3); assert.equal(h.redirects.length, 1);
});

test('刷新过程中退出或切换账号，不写入旧账号令牌或重放旧写操作', async () => {
  const h = harness(); const request = h.http.post('/project', { count: 2 });
  const rejected = assert.rejects(request, e => e.code === 'SESSION_CHANGED');
  h.respond(0, 401); await tick(); h.http.clearTokens(); h.http.setTokens('other-access', 'other-refresh');
  h.respond(1, 0, tokens(false)); await rejected;
  assert.equal(h.storage.get('zs_access_token'), 'other-access');
  assert.equal(h.calls.length, 2); assert.equal(h.redirects.length, 0);
});

test('刷新网络错误允许稍后重试，不清除有效刷新令牌', async () => {
  const h = harness(); const request = h.http.get('/project');
  const rejected = assert.rejects(request, e => e.errMsg === 'offline');
  h.respond(0, 401); await tick(); h.calls[1].fail({ errMsg: 'offline' }); await rejected;
  assert.equal(h.storage.get('zs_refresh_token'), 'old-refresh');
  assert.equal(h.redirects.length, 0);
});

// ---- P2#1 silent：后台/恢复请求抑制 showActivation 全局跳转，但会话状态照常同步 ----
// 与上面「刷新令牌无效时清会话且只跳转一次」「未激活业务码降级授权」互为对照：同一失效链路，
// 唯一差别是请求带 { silent: true }——清会话/降级授权快照照旧，唯不再把已离页的用户拉回激活页。

test('silent 恢复请求 401 且刷新失效时清会话但不全局跳转（P2#1 离页副作用隔离）', async () => {
  const h = harness();
  const request = h.http.get('/access-grant', undefined, { silent: true });
  const rejected = assert.rejects(request, e => e.code === 401);
  h.respond(0, 401); await tick(); h.respond(1, 401); await rejected;
  assert.equal(h.storage.has('zs_access_token'), false, 'silent 仍清会话（状态同步）');
  assert.equal(h.storage.has('zs_refresh_token'), false, 'silent 仍清刷新令牌');
  assert.equal(h.storage.get('v12Authorized'), false, 'silent 仍同步授权快照');
  assert.equal(Object.keys(h.app.globalData).length, 0, 'silent 仍清全局态');
  assert.equal(h.redirects.length, 0, 'silent 抑制 showActivation 全局跳转');
});

test('silent 恢复请求遇受限业务码只降级授权快照，不全局跳转（P2#1）', async () => {
  const h = harness();
  const request = h.http.get('/access-grant', undefined, { silent: true });
  h.respond(0, 1070001001);
  await assert.rejects(request, e => e.code === 1070001001);
  assert.equal(h.storage.get('zs_access_token'), 'old-access', '受限不清令牌（保留受限会话用于兑换）');
  assert.equal(h.storage.get('v12Authorized'), false, 'silent 仍降级授权快照');
  assert.equal(h.redirects.length, 0, 'silent 抑制 showActivation 全局跳转');
});

// ---- P1#1：silent 与前台请求共用同一次失效时，前台跳转不得被 silent 兄弟吞掉（两到达顺序均须跳转一次）----
// 复现的缺陷（round-2）：silent 兄弟先赢得清除处理→clearTokens 递增代际→前台守卫 getToken()===token 判否→前台不跳转（redirects=0）。
// 当前实现（round-3 事件机制 + round-4 会话归属）：拦截器确认 401 失效时 invalidateSession 把待决跳转绑定到被失效会话的 epoch
//   （pendingInvalidationEpoch）；前台请求经 foregroundInvalidateNav(silent, token, epoch, code) 四重门（前台 && 令牌快照非空 && 本响应为 401
//   && epoch 归属匹配）消费事件跳转恰一次（消费即复位去重）；无论清除由哪个分支/哪个请求（含 silent 兄弟先清）触发，迟到的同会话前台 401 都能补上、不被吞。

test('silent 后台请求先到达、与前台请求共用失败的刷新时，前台仍跳转一次（P1#1 silent-first）', async () => {
  const h = harness();
  const b = h.http.get('/access-grant', undefined, { silent: true }); // calls[0] silent 后台对账
  const a = h.http.get('/project');                                   // calls[1] 前台用户操作
  const rejectedB = assert.rejects(b, e => e.code === 401);
  const rejectedA = assert.rejects(a, e => e.code === 401);
  h.respond(0, 401); await tick();   // b 原请求 401 先处理 → b 创建本次刷新（refreshFlight）
  h.respond(1, 401); await tick();   // a 原请求 401 后处理 → a 加入同一刷新（同代际共用 refreshFlight.promise）
  h.respond(2, 401);                 // 共用刷新失败（calls[2]=token-refresh）
  await Promise.all([rejectedA, rejectedB]);
  assert.equal(h.storage.has('zs_access_token'), false, '失效会话已清');
  assert.equal(h.redirects.length, 1, 'silent 先赢得处理时前台仍须跳转一次（不被吞）');
});

test('前台请求先到达、与 silent 后台请求共用失败的刷新时，也只跳转一次（P1#1 foreground-first）', async () => {
  const h = harness();
  const a = h.http.get('/project');                                   // calls[0] 前台
  const b = h.http.get('/access-grant', undefined, { silent: true }); // calls[1] silent
  const rejectedA = assert.rejects(a, e => e.code === 401);
  const rejectedB = assert.rejects(b, e => e.code === 401);
  h.respond(0, 401); await tick();   // a 前台 401 先处理 → a 创建本次刷新（refreshFlight）
  h.respond(1, 401); await tick();   // b silent 401 后处理 → b 加入同一刷新（共用 refreshFlight.promise）
  h.respond(2, 401);                 // 共用刷新失败
  await Promise.all([rejectedA, rejectedB]);
  assert.equal(h.storage.has('zs_access_token'), false, '失效会话已清');
  assert.equal(h.redirects.length, 1, '前台先到达时也只跳转一次（不因 silent 兄弟漏跳或重复）');
});

// ---- P2#1 补强：silent 请求经「刷新成功后重放」与「并发轮换后 own-rotation 重放」再遇终态 401 时，仍清会话但不跳转 ----
// 覆盖 request.js 两处重放透传 options（刷新成功重放 / own-rotation 重放）与终态分支的 !silent 守卫；
// 此前 4 个 silent 用例均未触及重放路径，对「重放丢 options」「终态守卫去除」变异不敏感（codex round-1 P2）。

test('silent 请求刷新成功后重放又遇终态 401 时清会话但不跳转（P2#1 刷新成功重放路径）', async () => {
  const h = harness();
  const request = h.http.get('/access-grant', undefined, { silent: true }); // calls[0]
  const rejected = assert.rejects(request, e => e.code === 401);
  h.respond(0, 401); await tick();              // 原请求 401 → 刷新 calls[1]
  h.respond(1, 0, tokens(false)); await tick(); // 刷新成功轮换 → silent 重放 calls[2]
  h.respond(2, 401);                            // 重放后终态 401
  await rejected;
  assert.equal(h.storage.has('zs_access_token'), false, 'silent 重放终态仍清会话');
  assert.equal(h.storage.get('v12Authorized'), false, 'silent 重放终态仍降级授权快照');
  assert.equal(h.redirects.length, 0, 'silent 抑制重放终态的全局跳转');
});

test('silent 请求经并发轮换后 own-rotation 重放又遇终态 401 时清会话但不跳转（P2#1 own-rotation 重放路径）', async () => {
  const h = harness();
  const a = h.http.get('/a');                                          // calls[0] 前台，触发并成功刷新（轮换令牌）
  const b = h.http.get('/access-grant', undefined, { silent: true });  // calls[1] silent，其 401 迟到于轮换之后
  const rejectedB = assert.rejects(b, e => e.code === 401);
  h.respond(0, 401); await tick();              // a 原请求 401 → a 触发刷新 calls[2]
  h.respond(2, 0, tokens(false)); await tick(); // 刷新成功轮换（generation G→G+1、lastRotation 记录）
  h.respond(3, 0, 'a'); await a;                // a 重放成功 calls[3]（前台 a 正常完成、不跳转）
  h.respond(1, 401); await tick();              // b 原请求 401 迟到：命中 own-rotation 例外 → silent 重放 calls[4]
  h.respond(4, 401);                            // b own-rotation 重放后终态 401
  await rejectedB;
  assert.equal(h.storage.has('zs_access_token'), false, 'silent own-rotation 重放终态仍清会话');
  assert.equal(h.redirects.length, 0, 'silent 抑制 own-rotation 重放终态的全局跳转');
});

// ---- round-2 P1#1a/P1#1b/P1#2：foregroundInvalidateNav 助手统一裁决「前台失效跳转恰一次」的三条竞态回归 ----
// 上述 P1#1 两用例覆盖「前台请求加入同一在途失败刷新」（走刷新失败分支）；以下四例补齐 codex round-2 发现的更深竞态：
//   A 刷新「成功」后双终态 401（silent 重放先清、前台重放迟到走 SESSION_CHANGED 分支）；B 前台 401 迟到于 silent 兄弟清除之后
//   （走 SESSION_CHANGED 分支）；C1/C2 无 refresh token 分支（各自独立 rejected flight、非共享刷新）的两到达顺序。
// 四例均断言 redirects===1（前台恰跳一次、silent 从不跳、事件标志消费即复位防重复），实证见 .t13-08-race-probe.mjs（修复前 A/B/C1=0）。

test('silent 与前台共用「成功」刷新后双终态 401 时前台补跳一次（round-2 P1#1a 重放竞态）', async () => {
  const h = harness();
  const b = h.http.get('/access-grant', undefined, { silent: true }); // calls[0] silent 后台对账
  const a = h.http.get('/project');                                   // calls[1] 前台用户操作
  const rejectedB = assert.rejects(b, e => e.code === 401);           // silent 重放先清、抛原始终态 401
  const rejectedA = assert.rejects(a, e => e.code === 'SESSION_CHANGED'); // 前台重放迟到、会话已清 → SESSION_CHANGED
  h.respond(0, 401); h.respond(1, 401); await tick();   // b/a 原请求均 401 → 共用刷新 calls[2]
  h.respond(2, 0, tokens(false)); await tick();         // 刷新成功轮换 → 重放 calls[3]=b、calls[4]=a
  h.respond(3, 401); await tick();                      // b silent 重放终态 401 → 清会话、不跳
  h.respond(4, 401);                                    // a 前台重放终态 401 迟到 → 会话已清
  await Promise.all([rejectedA, rejectedB]);
  assert.equal(h.storage.has('zs_access_token'), false, '失效会话已清');
  assert.equal(h.redirects.length, 1, '刷新成功后 silent 重放先清会话时，前台重放仍补跳一次（不被吞）');
});

test('前台 401 迟到于 silent 兄弟清会话之后时补跳一次（round-2 P1#1b SESSION_CHANGED 分支）', async () => {
  const h = harness();
  const b = h.http.get('/access-grant', undefined, { silent: true }); // calls[0] silent
  const a = h.http.get('/project');                                   // calls[1] 前台，令牌快照 old-access
  const rejectedB = assert.rejects(b, e => e.code === 401);
  const rejectedA = assert.rejects(a, e => e.code === 'SESSION_CHANGED');
  h.respond(0, 401); await tick();   // b 原请求 401 → b 独占刷新 calls[2]
  h.respond(2, 401); await tick();   // 刷新失败 → b(silent) 清会话、不跳；a 尚未收到响应
  h.respond(1, 401);                 // a 前台 401 迟到：会话已被清 → 走 SESSION_CHANGED 分支
  await Promise.all([rejectedA, rejectedB]);
  assert.equal(h.storage.has('zs_access_token'), false, '失效会话已清');
  assert.equal(h.redirects.length, 1, '前台 401 迟到于 silent 兄弟清除之后仍补跳一次（SESSION_CHANGED 分支）');
});

test('无 refresh token 时 silent 先清会话、前台迟到补跳一次（round-2 P1#2 silent-first）', async () => {
  const h = harness(); h.storage.delete('zs_refresh_token'); // 无刷新令牌：401 后直接失效、不发起 token-refresh
  const b = h.http.get('/access-grant', undefined, { silent: true }); // calls[0] silent
  const a = h.http.get('/project');                                   // calls[1] 前台
  const rejectedB = assert.rejects(b, e => e.code === 401);
  const rejectedA = assert.rejects(a, e => e.code === 'SESSION_CHANGED');
  h.respond(0, 401); await tick();   // b 401 → 无 refresh → refreshSession 立即 reject → b(silent) 清会话、不跳
  h.respond(1, 401);                 // a 前台 401：会话已被清 → 走 SESSION_CHANGED 分支
  await Promise.all([rejectedA, rejectedB]);
  assert.equal(h.storage.has('zs_access_token'), false, '失效会话已清');
  assert.equal(h.redirects.length, 1, '无 refresh token 时 silent 先清会话，前台迟到仍补跳一次');
});

test('无 refresh token 时前台先清会话跳一次、silent 兄弟不重复跳（round-2 P1#2 foreground-first）', async () => {
  const h = harness(); h.storage.delete('zs_refresh_token');
  const a = h.http.get('/project');                                   // calls[0] 前台
  const b = h.http.get('/access-grant', undefined, { silent: true }); // calls[1] silent
  const rejectedA = assert.rejects(a, e => e.code === 401);           // 前台经刷新失败分支清会话、抛原始 401
  const rejectedB = assert.rejects(b, e => e.code === 'SESSION_CHANGED'); // silent 迟到、会话已清 → SESSION_CHANGED
  h.respond(0, 401); await tick();   // a 前台 401 → 无 refresh → 立即 reject → a 清会话 + foregroundInvalidateNav 跳一次
  h.respond(1, 401);                 // b silent 401：会话已被清 → SESSION_CHANGED、silent 不跳（事件标志亦防重复）
  await Promise.all([rejectedA, rejectedB]);
  assert.equal(h.storage.has('zs_access_token'), false, '失效会话已清');
  assert.equal(h.redirects.length, 1, '无 refresh token 时前台先清会话跳一次，silent 兄弟不重复跳');
});

test('两前台请求共用同一次失效时只跳转一次（round-2 事件标志消费即复位去重）', async () => {
  const h = harness();
  const a1 = h.http.get('/project-1');  // calls[0] 前台请求一
  const a2 = h.http.get('/project-2');  // calls[1] 前台请求二（与 a1 同代际、令牌快照同为 old-access）
  const rejectedA1 = assert.rejects(a1, e => e.code === 401);
  const rejectedA2 = assert.rejects(a2, e => e.code === 401);
  h.respond(0, 401); await tick();   // a1 401 → 创建本次刷新 calls[2]
  h.respond(1, 401); await tick();   // a2 401 → 加入同一刷新（共用 refreshFlight.promise）
  h.respond(2, 401);                 // 共用刷新失败：首个处理者 invalidateSession + 跳一次，第二个经标志消费即复位去重不重复跳
  await Promise.all([rejectedA1, rejectedA2]);
  assert.equal(h.storage.has('zs_access_token'), false, '失效会话已清（只清一次）');
  assert.equal(h.redirects.length, 1, '两前台请求共用同一次失效时只跳转一次（事件标志消费即复位去重，移除则双重跳转）');
});

// ---- round-3 P1#1/P1#2：事件「消费门」的五条 =0 竞态回归（code 门 + token 门；round-4 另增 epoch 归属门，见本节末两例）----
// round-2 用例断言 redirects===1（合法前台 401 恰跳一次）；以下补齐 codex round-3 重审发现的「不应跳」判据——事件置位后，
//   仅凭置位不足以跳转，还须 token 快照非空（请求属某会话）&& 本响应确为 401（非迟到 200/500/SESSION_CHANGED）。五例均断言 redirects===0。
//   鉴别力（round-4 变异矩阵 .t13-08-r4-mutation.mjs 实证）：late-200/late-500 鉴别 M_code（a 创建于失效前、epoch 匹配，唯 code 门拦截）、
//   superseding-login 鉴别 M_reset_login。tokenless 例（本节）属「不变量」——g 创建于 clearTokens 之后、epoch 已递增≠pending，由 epoch 门拦截；
//   M_token 的独立鉴别另见 round-5「存储读失败」例（该边 epoch 仍匹配 pending、唯 token 门拦截，M_token 转 CAUGHT）；M_epoch 的独立鉴别见本节末 X1/X2。
//   logout-late-success 为对照（round-4 P2#2 已证其对复位不具鉴别力，鉴别版见 round-4 节 logout-late-foreground-401）。round-3 探针见 .t13-08-r3-probe.mjs。

test('silent 失效置位标志后，前台兄弟迟到的成功响应不消费标志跳转（round-3 P1#1a 迟到 200）', async () => {
  const h = harness();
  const b = h.http.get('/access-grant', undefined, { silent: true }); // calls[0] silent 后台对账
  const a = h.http.get('/project');                                   // calls[1] 前台，令牌快照 old-access
  const rejectedB = assert.rejects(b, e => e.code === 401);
  const rejectedA = assert.rejects(a, e => e.code === 'SESSION_CHANGED');
  h.respond(0, 401); await tick();   // b 401 → b 独占刷新 calls[2]
  h.respond(2, 401); await tick();   // 刷新失败 → b(silent) invalidateSession：清会话 + 置位标志、不跳
  h.respond(1, 0, { ok: 1 });        // a 原请求迟到「成功 200」→ .then 判会话已变 → SESSION_CHANGED 分支
  await Promise.all([rejectedA, rejectedB]);
  assert.equal(h.storage.has('zs_access_token'), false, '失效会话已清');
  assert.equal(h.redirects.length, 0, '迟到的成功响应非 401，不得消费标志触发后台导航（code 门，鉴别 M_code）');
});

test('silent 失效置位标志后，前台兄弟迟到的 500 错误不消费标志跳转（round-3 P1#1b 迟到 500）', async () => {
  const h = harness();
  const b = h.http.get('/access-grant', undefined, { silent: true }); // calls[0] silent
  const a = h.http.get('/project');                                   // calls[1] 前台
  const rejectedB = assert.rejects(b, e => e.code === 401);
  const rejectedA = assert.rejects(a, e => e.code === 'SESSION_CHANGED');
  h.respond(0, 401); await tick();
  h.respond(2, 401); await tick();   // silent 失效置位标志
  h.respond(1, 500, null, 500);      // a 迟到「500 服务器错误」→ .catch → 会话已变分支 → code!==401 不消费标志
  await Promise.all([rejectedA, rejectedB]);
  assert.equal(h.storage.has('zs_access_token'), false, '失效会话已清');
  assert.equal(h.redirects.length, 0, '迟到的服务器错误(500)非 401，不得消费标志触发后台导航（code 门，鉴别 M_code）');
});

test('silent 单独失效后，无令牌新起前台请求遇 401 不消费残留标志（round-3 P1#2 tokenless）', async () => {
  const h = harness();
  const b = h.http.get('/access-grant', undefined, { silent: true }); // calls[0] silent
  const rejectedB = assert.rejects(b, e => e.code === 401);
  h.respond(0, 401); await tick();   // b 401 → b 独占刷新 calls[1]
  h.respond(1, 401); await tick();   // 刷新失败 → b(silent) invalidateSession：清会话 + 置位标志、不跳
  const g = h.http.get('/project');  // calls[2]：会话已清 → 令牌快照 ''（无令牌新起前台请求）
  const rejectedG = assert.rejects(g, e => e.code === 401);
  h.respond(2, 401);                 // g 401 → 无 token 不入刷新分支 → 终态分支：g 的 epoch 已被 clearTokens 递增≠pending，epoch 门拦截不消费残留事件（token 门的独立承重见 round-5 存储读失败例）
  await Promise.all([rejectedB, rejectedG]);
  assert.equal(h.storage.has('zs_access_token'), false, '失效会话已清');
  assert.equal(h.redirects.length, 0, '无令牌新起请求不属于被失效会话，不得消费残留事件（本例 epoch 已递增≠pending、由 epoch 门拦截；token 门独立承重见 round-5 存储读失败例）');
});

test('silent 失效后新登录取代旧会话，前台迟到 401 不跳转（round-3 对照：setTokens 复位标志，鉴别 M_reset）', async () => {
  const h = harness();
  const b = h.http.get('/access-grant', undefined, { silent: true }); // calls[0] silent
  const a = h.http.get('/project');                                   // calls[1] 前台，令牌快照 old-access
  const rejectedB = assert.rejects(b, e => e.code === 401);
  const rejectedA = assert.rejects(a, e => e.code === 'SESSION_CHANGED');
  h.respond(0, 401); await tick();   // b 401 → b 独占刷新 calls[2]
  h.respond(2, 401); await tick();   // 刷新失败 → b(silent) invalidateSession：置位标志
  h.http.setTokens('fresh-login', 'fresh-ref'); // 新登录取代：复位标志、gen++、令牌=fresh-login
  h.respond(1, 401);                 // a 前台 401 迟到 → 会话已被新登录取代 → SESSION_CHANGED 分支
  await Promise.all([rejectedA, rejectedB]);
  assert.equal(h.storage.get('zs_access_token'), 'fresh-login', '新登录令牌保留，未被迟到响应清除');
  assert.equal(h.redirects.length, 0, '新登录已复位标志，绝不把刚登录的用户拉回激活页（鉴别 M_reset）');
});

test('显式登出后迟到的成功响应不跳转（round-3 对照：clearTokens 复位标志）', async () => {
  const h = harness();
  const a = h.http.get('/project');  // calls[0] 前台，令牌快照 old-access
  const rejectedA = assert.rejects(a, e => e.code === 'SESSION_CHANGED');
  h.http.clearTokens();              // 显式登出：复位标志、清会话
  h.respond(0, 0, { ok: 1 });        // a 迟到成功 200 → 会话已变 → SESSION_CHANGED 分支
  await rejectedA;
  assert.equal(h.storage.has('zs_access_token'), false, '登出后会话保持清除');
  assert.equal(h.redirects.length, 0, '登出已复位标志，迟到的成功响应不触发跳转');
});

// ---- round-4 P1#1：pendingInvalidationEpoch 事件「会话归属」的两条跨会话竞态回归（foregroundInvalidateNav 第四重门 epoch 匹配）----
// round-3 的布尔标志只编码「曾发生一次失效」而不记「哪个会话失效」；以下两例复现 codex round-4 发现的跨会话误消费：
//   前台请求 A（会话 A）起始后，用户登录 B（新会话）、B 又静默失效置位事件；A 迟到的 401 若仅凭 token 非空 + code===401 会误消费 B 的事件、
//   把已与 A 无关的用户拉回激活页（实证 redirects=1，应 0）。绑定 epoch 后 A 的 epoch ≠ B 被失效的 epoch → 不消费。实证见仓库外探针
//   .t13-08-r4-probe.mjs（修复前 X1/X2=1，对照 ctrlRotationPreserved=1 验证轮换归属仍保留）。鉴别 M_epoch（移除 epoch 归属门）变异。

test('被取代的旧会话前台请求不消费新会话的失效事件（round-4 P1#1 跨会话 original-response）', async () => {
  const h = harness();
  const a = h.http.get('/project');                                   // calls[0] 前台 A，令牌快照 old-access（会话 A）
  const rejectedA = assert.rejects(a, e => e.code === 'SESSION_CHANGED');
  h.http.setTokens('B-access', 'B-ref');                              // 登录 B：新逻辑会话（epoch++、复位待决事件）
  const b = h.http.get('/access-grant', undefined, { silent: true }); // calls[1] B 静默对账
  const rejectedB = assert.rejects(b, e => e.code === 401);
  h.respond(1, 401); await tick();   // B 静默 401 → B 刷新 calls[2]
  h.respond(2, 401); await tick();   // B 刷新失败 → B(silent) invalidateSession：绑定 B 的 epoch、清会话、不跳
  h.respond(0, 401);                 // A 原请求迟到 401 → 会话已被 B 取代并失效 → SESSION_CHANGED 分支
  await Promise.all([rejectedA, rejectedB]);
  assert.equal(h.storage.has('zs_access_token'), false, '失效会话已清');
  assert.equal(h.redirects.length, 0, 'A 属被取代的旧会话，epoch 不匹配，不得消费 B 的失效事件跳转（鉴别 M_epoch）');
});

test('被取代的旧会话前台刷新失败不消费新会话的失效事件（round-4 P1#1 跨会话 refresh-rejection）', async () => {
  const h = harness();
  const a = h.http.get('/project');                                   // calls[0] 前台 A，令牌快照 old-access（会话 A）
  const rejectedA = assert.rejects(a, e => e.code === 401);
  h.respond(0, 401); await tick();   // A 401 → A 触发自身刷新 calls[1]（会话 A 的 refreshFlight）
  h.http.setTokens('B-access', 'B-ref');                              // 登录 B：新逻辑会话（epoch++、复位待决事件）
  const b = h.http.get('/access-grant', undefined, { silent: true }); // calls[2] B 静默
  const rejectedB = assert.rejects(b, e => e.code === 401);
  h.respond(2, 401); await tick();   // B 静默 401 → B 刷新 calls[3]
  h.respond(3, 401); await tick();   // B 刷新失败 → B(silent) invalidateSession：绑定 B 的 epoch
  h.respond(1, 401);                 // A 的刷新迟到 reject → A 刷新失败分支：会话 A ≠ 失效的会话 B
  await Promise.all([rejectedA, rejectedB]);
  assert.equal(h.storage.has('zs_access_token'), false, '失效会话已清');
  assert.equal(h.redirects.length, 0, 'A 的刷新属被取代的旧会话，epoch 不匹配，不误跳（鉴别 M_epoch）');
});

// ---- round-4 P2#2：显式登出复位待决事件的鉴别回归（补足既有「登出后迟到成功响应」用例不具鉴别力的缺口）----
// 既有 ctrlLogoutLateSuccess 走「迟到成功 200」→ 被 code 门（非 401）独立拦截，故移除 clearTokens 的事件复位仍全绿、无法鉴别该复位。
// 本例改「先 silent 失效置位事件 → 显式登出 → 此前起始的前台迟到 401」：token 门（非空）与 code 门（401）均通过，唯 clearTokens 的
// epoch 复位（pendingInvalidationEpoch=null）能拦截；移除该复位则前台迟到 401 误跳（redirects 0→1）。鉴别 M_reset_logout 变异。
test('显式登出复位事件后，此前起始的前台迟到 401 不跳转（round-4 P2#2 鉴别 M_reset_logout）', async () => {
  const h = harness();
  const b = h.http.get('/access-grant', undefined, { silent: true }); // calls[0] silent
  const a = h.http.get('/project');                                   // calls[1] 前台 A，令牌快照 old-access
  const rejectedB = assert.rejects(b, e => e.code === 401);
  const rejectedA = assert.rejects(a, e => e.code === 'SESSION_CHANGED');
  h.respond(0, 401); await tick();   // b 401 → b 独占刷新 calls[2]
  h.respond(2, 401); await tick();   // 刷新失败 → b(silent) invalidateSession：绑定当前 epoch、置位待决事件
  h.http.clearTokens();              // 显式登出：epoch++、复位待决事件为 null
  h.respond(1, 401);                 // a 前台迟到「401」→ 会话已变分支：token 门(非空)、code 门(401)均过，唯登出复位拦截
  await Promise.all([rejectedA, rejectedB]);
  assert.equal(h.storage.has('zs_access_token'), false, '登出后会话保持清除');
  assert.equal(h.redirects.length, 0, '登出已复位待决事件，前台迟到 401 不触发跳转（移除 clearTokens 复位则误跳=1，鉴别 M_reset_logout）');
});

// ---- round-5 P1#1：刷新「成功」后→重放前的微任务窗口内会话被取代（登录/登出），旧写操作不得跨会话重放 ----
// codex round-5 发现：refreshSession 内部守卫只在「写轮换令牌」那一刻校验代际，而 successCb 重放是其后的独立微任务；若此窗口内 setTokens/clearTokens
//   改变 sessionEpoch，直接重放会用「新会话令牌」发出「旧会话的操作」（跨会话泄漏），且重放捕获新 epoch 后其迟到 401 会被第四门误当作新会话失效而误跳
//   （探针 .t13-08-r5-p11-probe.mjs 实证 auth=Bearer B-access、redirects=1）。修复：successCb 重放前校验 sessionEpoch!==epoch → 抛 SESSION_CHANGED、不重放。
//   与既有「刷新过程中退出或切换账号」（切换发生在刷新响应「之前」、由 refreshSession 内部守卫拦截）互补，本二例覆盖切换发生在刷新成功「之后、重放之前」的窗口。

test('刷新成功后、重放前的窗口内登录新会话，旧写操作不被跨会话重放（round-5 P1#1 login-window）', async () => {
  const h = harness();
  const req = h.http.post('/project', { count: 2 });   // calls[0] A 的写操作（会话 A、epoch=0）
  const rejected = assert.rejects(req, e => e.code === 'SESSION_CHANGED');
  h.respond(0, 401); await tick();                   // A 401 → refreshSession 发起 calls[1]=token-refresh
  h.respond(1, 0, tokens(false));                    // 刷新「成功」→ writeTokens 进入微任务队列（epoch 仍为 A）
  await Promise.resolve(); await Promise.resolve();  // 推进到「已写轮换令牌、successCb 重放尚未执行」的窗口
  assert.equal(h.storage.get('zs_access_token'), 'new-access', '窗口内已写入 A 的轮换令牌');
  assert.equal(h.calls.length, 2, '窗口内尚未重放');
  h.http.setTokens('B-access', 'B-ref');             // 窗口内登录 B：epoch++（新逻辑会话）
  await tick();                                      // successCb：sessionEpoch(B)!==epoch(A) → 抛 SESSION_CHANGED、不重放
  assert.equal(h.calls.length, 2, '旧写操作未被重放（无第三次调用）');  // 先于 await rejected 断言：移除守卫的变异会立即产生第三次调用而 fail-fast，不致在 await 处挂起
  await rejected;
  assert.ok(!h.calls.some((c, i) => i > 0 && c.data && c.data.count === 2), 'A 的旧写操作绝不用 B 令牌跨会话重放');
  assert.equal(h.storage.get('zs_access_token'), 'B-access', 'B 的新登录令牌保留、未被旧请求清除');
  assert.equal(h.redirects.length, 0, '会话被新登录取代，绝不把刚登录的用户拉回激活页');
});

test('刷新成功后、重放前的窗口内登出，旧写操作不被重放（round-5 P1#1 logout-window）', async () => {
  const h = harness();
  const req = h.http.post('/project', { count: 2 });   // calls[0] A 的写操作
  const rejected = assert.rejects(req, e => e.code === 'SESSION_CHANGED');
  h.respond(0, 401); await tick();
  h.respond(1, 0, tokens(false));
  await Promise.resolve(); await Promise.resolve();  // 窗口：已写轮换令牌、未重放
  assert.equal(h.calls.length, 2, '窗口内尚未重放');
  h.http.clearTokens();                              // 窗口内显式登出：epoch++、清会话
  await tick();                                      // successCb：sessionEpoch!==epoch → 抛 SESSION_CHANGED、不重放
  assert.equal(h.calls.length, 2, '登出后旧写操作不被重放');  // 先于 await rejected 断言：移除守卫的变异 fail-fast，不致挂起
  await rejected;
  assert.equal(h.storage.has('zs_access_token'), false, '登出后会话保持清除');
  assert.equal(h.redirects.length, 0, '登出后旧写操作的重放不触发跳转');
});

// ---- round-5 P2#1：token 门（②）的独立承重——存储读失败致令牌快照为空、但 epoch 仍属被失效会话 ----
// codex round-5 反驳 round-4「该边 harness 不可复现、② 与 ④ 冗余」的定性：前台请求创建期令 storage 对 access token 抛错，getToken() 落 catch 返回 ''，
//   但 sessionEpoch 仍为当前会话 E；同 epoch 的 silent 请求失效后 pending=E，前台迟到 401 时 ④ epoch 门因 E===E「放行」，唯 ② token 门（快照为空）拦截。
//   本例鉴别 M_token：移除 ② 则 redirects=1（误跳）。变异实证见 .t13-08-r5-mutation.mjs。
test('前台请求创建期 storage 读失败致令牌快照空、epoch 仍属被失效会话时不跳转（round-5 P2#1 存储读失败，鉴别 M_token）', async () => {
  const h = harness();
  const origGet = h.storage.get;
  h.storage.get = function (key) { if (key === 'zs_access_token') throw new Error('storage read unavailable'); return origGet.call(this, key); };
  const a = h.http.get('/project');                 // calls[0] 前台：getToken() 抛错落 catch → token 快照 ''、epoch=E
  h.storage.get = origGet;                          // 恢复读取
  const rejectedA = assert.rejects(a, e => e.code === 'SESSION_CHANGED');
  const b = h.http.get('/access-grant', undefined, { silent: true });  // calls[1] silent（token=old-access、epoch=E）
  const rejectedB = assert.rejects(b, e => e.code === 401);
  h.respond(1, 401); await tick();   // b 401 → b 刷新 calls[2]
  h.respond(2, 401); await tick();   // b 刷新失败 → invalidateSession：绑定 pending=E、clearTokens→epoch=E+1、清会话
  h.respond(0, 401);                 // a 前台迟到 401 → 会话已清 → SESSION_CHANGED 分支：foregroundInvalidateNav(false,'',E,401)
  await Promise.all([rejectedA, rejectedB]);
  assert.equal(h.storage.has('zs_access_token'), false, '失效会话已清');
  assert.equal(h.redirects.length, 0, 'token 快照为空的前台请求不消费待决事件（④ epoch 因匹配 E 放行、唯 ② token 门拦截；移除 ② 的 M_token 则误跳=1）');
});
