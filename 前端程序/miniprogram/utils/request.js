'use strict';

const config = require('./config');

const TOKEN_KEY = 'zs_access_token';
const REFRESH_KEY = 'zs_refresh_token';
const REFRESH_URL = '/app-api/design/v1/auth/token-refresh';
const GRANT_REQUIRED = 1070001001;
let sessionGeneration = 0;   // 每次令牌变化（刷新轮换 / 新登录 / 登出失效）都自增——既有 SESSION_CHANGED 与轮换守卫依赖它（语义不变）
let sessionEpoch = 0;        // 逻辑会话身份：仅「新登录」与「登出/失效清除」自增，刷新轮换「保留」同一 epoch——用于给失效跳转事件绑定归属会话（round-4）
let refreshFlight = null;
let lastRotation = null;
// 「拦截器已确认某个逻辑会话因 401 失效、待补一次前台跳转」的事件归属（round-4 取代 round-3 的无归属布尔标志 pendingInvalidationNav）：
//   记录被失效会话的 epoch。round-3 的布尔标志只编码「曾发生一次失效」而不记「哪个会话失效」，故跨会话竞态下（前台请求 A 起始→
//   登录 B→B 静默失效置位标志→A 迟到 401）A 会误消费 B 的失效事件、把已与当前操作无关的用户拉回激活页（codex round-4 P1#1，实证 redirects=1）。
//   绑定 epoch 后，仅「与被失效会话同一逻辑 epoch」的前台请求能消费；epoch 跨刷新轮换保留（同一会话轮换令牌不改归属）、仅登录/登出变更。
//   仅由 invalidateSession 置位、由 setTokens（新登录）与 clearTokens（登出）复位为 null。是否消费另由 foregroundInvalidateNav 四重门裁决
//   （前台 && 令牌快照非空 && 本响应为 401 && epoch 归属匹配），详见该函数注释。
let pendingInvalidationEpoch = null;

function getToken() {
  try { return wx.getStorageSync(TOKEN_KEY) || ''; } catch (e) { return ''; }
}
// 纯令牌写入 + 代际自增；刷新「轮换」用它——同一逻辑会话，epoch 保留、不改失效跳转归属（round-4）。
function writeTokens(access, refresh) {
  wx.setStorageSync(TOKEN_KEY, access || '');
  if (refresh) wx.setStorageSync(REFRESH_KEY, refresh);
  else wx.removeStorageSync(REFRESH_KEY);
  sessionGeneration++;
  lastRotation = null;
}
// 新登录：建立「新逻辑会话」——epoch 自增、复位任何待决失效跳转（撤销归属，绝不把刚登录的用户拉回激活页）。
function setTokens(access, refresh) {
  writeTokens(access, refresh);
  sessionEpoch++;
  pendingInvalidationEpoch = null;
}
// 登出/失效清除：结束当前逻辑会话——epoch 自增、复位待决跳转（登出后迟到的在途 401 不应把用户拉回激活页）。
function clearTokens() {
  wx.removeStorageSync(TOKEN_KEY);
  wx.removeStorageSync(REFRESH_KEY);
  // P2-C（报告 15）：随会话清除预算草稿作用域——账号切换后新会话使用新作用域，
  // 令牌静默刷新（setTokens）不清除，保证草稿跨刷新存续。
  wx.removeStorageSync('zs_draft_scope');
  sessionGeneration++;
  sessionEpoch++;
  lastRotation = null;
  try { wx.setStorageSync('v12Authorized', false); } catch (e) { /* 存储失败仍保持令牌清除 */ }
  if (typeof getApp === 'function') getApp().globalData = {};
  pendingInvalidationEpoch = null;
}

// 拦截器判定会话因 401 确定失效时清会话并「布防」一次前台失效跳转；与显式 clearTokens（登出）区别在于把待决跳转绑定到被失效会话的 epoch。
// clearTokens 会自增 epoch 并复位 pendingInvalidationEpoch，故须在其「之前」捕获被失效会话的 epoch，布防后仅同一逻辑会话的前台请求能消费。
// 由刷新失败分支与重放终态分支在各自守卫内调用（守卫保证每次会话死亡只清一次），随后到达的同会话前台请求经 foregroundInvalidateNav 消费一次（silent 不消费）。
function invalidateSession() {
  const invalidatedEpoch = sessionEpoch;
  clearTokens();
  pendingInvalidationEpoch = invalidatedEpoch;
}

function rawRequest(options) {
  return new Promise((resolve, reject) => {
    wx.request({
      url: config.apiBase + options.url,
      method: options.method || 'GET',
      data: options.data,
      header: Object.assign({
        'Content-Type': 'application/json',
        'tenant-id': String(config.tenantId)
      }, getToken() ? { 'Authorization': 'Bearer ' + getToken() } : {}, options.header || {}),
      success: resolve,
      fail: reject
    });
  });
}

function responseData(res) {
  const body = res.data;
  const code = res.statusCode === 401 ? 401 : res.statusCode === 403 ? 403
    : body && typeof body.code === 'number' ? body.code : res.statusCode >= 400 ? res.statusCode : 0;
  if (code !== 0) throw { code, msg: body && body.msg || '请求失败(' + code + ')' };
  return body && typeof body.code === 'number' ? body.data : body;
}

function showActivation() {
  const pages = getCurrentPages();
  const current = pages[pages.length - 1];
  if (!current || current.route !== 'pages/auth/index') wx.redirectTo({ url: '/pages/auth/index' });
}

// 前台请求消费「待决失效跳转」事件、跳转激活页恰一次。四重门缺一不可（codex round-3 P1#1/P1#2 + round-4 P1#1 实证）：
//   ① !silent——silent 后台/离页请求从不触发全局跳转（承 T13-07 P2#1）；
//   ② token——请求发起时的令牌快照非空，即本请求确曾「属于」某个会话；无令牌请求（token=''）不得消费待决失效事件（round-3 P1#2 不变量）。
//      ② 与 ④ 并非冗余、各自独立承重：clearTokens 后新起的无令牌请求 epoch 已递增≠pending，由 ④ 拦截；但「前台请求创建期 storage 读失败致
//      getToken()='' 而 epoch 仍匹配 pending」这一边（该请求本属被失效的同一 epoch，④ 因 epoch 匹配而放行），唯有 ② 能拦截。codex round-5 探针
//      实证此边可复现（原代码 redirects=0、移除 ② 的 M_token redirects=1）——故 ② 由 round-5「存储读失败」回归测试独立守护、M_token 转 CAUGHT；
//   ③ code === 401——本次迟到响应确是「401 失效」，而非迟到的成功(200)/服务器错误(500)/显式会话变更(SESSION_CHANGED)（round-3 P1#1）；
//   ④ pendingInvalidationEpoch === epoch——待决事件归属的逻辑会话，正是本请求发起时所属的那个会话（round-4 P1#1）：仅有 ②③ 不足以
//      确立归属，跨会话竞态下（前台 A 起始→登录 B→B 静默失效→A 迟到 401）A 的 token 非空、code 亦为 401，却会误消费 B 的失效事件。
//      epoch 跨刷新「轮换」保留（同一会话换令牌不改归属，故轮换后前台迟到 401 仍能补跳）、仅「新登录/登出」变更，令归属精确到逻辑会话。
//   事件仅由 invalidateSession（拦截器 401 失效）绑定被失效会话的 epoch，由 setTokens（新登录）与 clearTokens（登出）复位为 null——故登出/
//   新登录后迟到的在途响应不会误跳；消费即复位（=null），令同一逻辑会话跨刷新轮换/多代际/并发前台合计只跳一次。
function foregroundInvalidateNav(silent, token, epoch, code) {
  if (!silent && token && code === 401 && pendingInvalidationEpoch === epoch) {
    pendingInvalidationEpoch = null;
    showActivation();
  }
}

function sessionChanged() { return { code: 'SESSION_CHANGED', msg: '登录身份已变化，请重新进入' }; }

function isSameSession(token) {
  return !!token && (getToken() === token || !!(lastRotation && lastRotation.access === token
    && lastRotation.nextAccess === getToken() && lastRotation.nextGeneration === sessionGeneration));
}

// 同代际的并发 401 共用一次刷新（refreshFlight 去重），返回裸 promise。导航责任不在此——由 request() 各失效分支经
//   foregroundInvalidateNav（事件 epoch 归属 + 四重门）统一裁决恰一次跳转，故刷新本身无需区分 silent/前台（round-2 P1#1/P1#2 由此收敛）。
function refreshSession() {
  const generation = sessionGeneration, access = getToken();
  const refresh = wx.getStorageSync(REFRESH_KEY);
  if (!refresh) return Promise.reject({ code: 401, msg: '请重新登录' });
  if (refreshFlight && refreshFlight.generation === generation) return refreshFlight.promise;
  const flight = { generation };
  flight.promise = rawRequest({ url: REFRESH_URL, method: 'POST', data: { refreshToken: refresh } })
    .then(responseData).then(tokens => {
      if (sessionGeneration !== generation || getToken() !== access || wx.getStorageSync(REFRESH_KEY) !== refresh) throw sessionChanged();
      if (!tokens || !tokens.accessToken || !tokens.refreshToken || typeof tokens.restricted !== 'boolean') {
        throw { code: 401, msg: '会话刷新失败，请重新登录' };
      }
      writeTokens(tokens.accessToken, tokens.refreshToken);   // 轮换：同一逻辑会话，epoch 保留（不用 setTokens，后者会新建逻辑会话）
      lastRotation = { access, generation, nextAccess: tokens.accessToken, nextGeneration: sessionGeneration };
      wx.setStorageSync('v12Authorized', !tokens.restricted);
    }).finally(() => { if (refreshFlight === flight) refreshFlight = null; });
  refreshFlight = flight;
  return flight.promise;
}

// 业务码和 HTTP 401 使用同一刷新路径；资源 403 不清会话。
// 原请求最多重放一次，保留原始幂等键；身份改变后不重放旧用户操作。
// options.silent：后台/恢复请求（app onShow 的 refreshFromServer、激活页在途授权查询）专用——会话状态照常
//   同步（clearTokens、v12Authorized 落盘），但抑制 showActivation 的全局跳转：请求在途时用户可能已离页，
//   迟到的 401/受限不应把用户从当前页强制拉回激活页（承 T13-07 P2#1）；导航改由页面级门禁在用户交互时驱动。
function request(url, method, data, extraHeaders, retried, options) {
  const silent = !!(options && options.silent);
  const token = getToken(), generation = sessionGeneration, epoch = sessionEpoch;
  return rawRequest({ url, method, data, header: extraHeaders }).then(responseData).then(result => {
    const ownRotation = lastRotation && lastRotation.access === token && lastRotation.generation === generation
      && lastRotation.nextAccess === getToken() && lastRotation.nextGeneration === sessionGeneration;
    if ((getToken() !== token || sessionGeneration !== generation) && !ownRotation) throw sessionChanged();
    return result;
  }).catch(error => {
    if (getToken() !== token || sessionGeneration !== generation) {
      if (!retried && error.code === 401 && lastRotation && lastRotation.access === token
          && lastRotation.generation === generation && lastRotation.nextAccess === getToken()
          && lastRotation.nextGeneration === sessionGeneration) {
        return request(url, method, data, extraHeaders, true, options);
      }
      // 前台请求的响应迟到于会话已被并发失效清除（silent 兄弟或轮换后的重放先赢得清除）：仅当①本请求属某会话（token 快照非空）
      //   ②本次迟到响应确为 401 失效（error.code===401）③待决事件归属的逻辑会话正是本请求发起时的会话（epoch 匹配，清除源于拦截器
      //   401 失效）三者同时成立，才补上这一次跳转；迟到的成功(200)/服务器错误(500)/显式登出或新登录取代（事件已复位）/被取代的旧会话
      //   （epoch 不匹配）均不跳（round-3 P1#1：不可仅凭事件置位跳转；round-4 P1#1：不可仅凭 token+401 跳转，须 epoch 归属匹配）。
      foregroundInvalidateNav(silent, token, epoch, error.code);
      throw sessionChanged();
    }
    if (error.code === GRANT_REQUIRED) {
      wx.setStorageSync('v12Authorized', false);
      if (!silent) showActivation();
      throw error;
    }
    if (error.code !== 401 || url === REFRESH_URL || url.endsWith('/auth/wechat-login')) throw error;
    if (!retried && token) {
      return refreshSession().then(() => {
        // 刷新成功→重放之间是独立微任务：refreshSession 内部守卫只在「写轮换令牌」那一刻校验代际，而 successCb 重放发生在其后。
        // 若此微任务窗口内发生登录/登出（sessionEpoch 变更），直接重放会用「新会话令牌」发出「旧会话的操作」（跨会话泄漏），且重放会捕获
        // 新 epoch、其迟到 401 反被第四门当作新会话的合法失效而误跳（codex round-5 P1#1，探针实证 auth=Bearer B-access、redirects=1）。
        // epoch 仅登录/登出变更、轮换保留，故 sessionEpoch!==epoch 精确等价「刷新期间逻辑会话已被取代」→ 抛 SESSION_CHANGED、绝不重放旧操作。
        if (sessionEpoch !== epoch) throw sessionChanged();
        return request(url, method, data, extraHeaders, true, options);
      }, refreshError => {
        // 刷新失败即本代际会话确定失效：清一次令牌并布防失效跳转（invalidateSession，绑定本会话 epoch）。守卫 getToken()===token && 代际未变
        //   天然只被首个处理者命中——并发等待者（silent 与前台）只清一次；该守卫同时保留「刷新等待期新登录取代旧会话」的
        //   SESSION_CHANGED 保护（新令牌→getToken()!==token→不清、事件亦已被 setTokens 复位）。跳转经 foregroundInvalidateNav
        //   四重门裁决（前台 && token 快照非空 && refreshError.code===401 && 事件 epoch 归属匹配）：即便 silent 兄弟先赢得清除并布防，迟到
        //   的同会话前台「401」请求仍补上一次跳转、不被吞（round-2 P1#1），而被取代的旧会话前台请求因 epoch 不匹配不误跳（round-4 P1#1）；
        //   刷新遇网络错误（refreshError.code≠401）时守卫判否、不清不布防，且 code 门亦拦截→不跳（保留会话供稍后重试）。
        if (refreshError.code === 401 && getToken() === token && sessionGeneration === generation) invalidateSession();
        foregroundInvalidateNav(silent, token, epoch, refreshError.code);
        throw refreshError;
      });
    }
    // 重放终态分支：已重放又 401（error.code===401 已由上面「非 401/刷新/登录端点则原样抛出」的前置守卫保证），会话确定
    //   失效——token 非空则清一次令牌并布防失效跳转（invalidateSession，绑定本会话 epoch），随后前台经 foregroundInvalidateNav 四重门跳转一次
    //   （前台 && token 非空 && code===401 && 事件 epoch 归属匹配）；silent 不跳；token 为空则本就无会话、不清不布防，且 token 门拦截→不跳
    //   （round-3 P1#2：无令牌新起请求不得消费此前会话遗留的待决事件，与 HEAD 既有行为一致）。
    if (token) invalidateSession();
    foregroundInvalidateNav(silent, token, epoch, error.code);
    throw error;
  });
}

module.exports = {
  get: function (url, extraHeaders, options) { return request(url, 'GET', undefined, extraHeaders, false, options); },
  post: function (url, data, extraHeaders, options) { return request(url, 'POST', data, extraHeaders, false, options); },
  put: function (url, data, extraHeaders, options) { return request(url, 'PUT', data, extraHeaders, false, options); },
  del: function (url, data, extraHeaders, options) { return request(url, 'DELETE', data, extraHeaders, false, options); },
  patch: function (url, data, extraHeaders, options) { return request(url, 'PATCH', data, extraHeaders, false, options); },
  rawRequest: rawRequest,
  getToken: getToken,
  isSameSession: isSameSession,
  setTokens: setTokens,
  clearTokens: clearTokens,
  config: config
};
