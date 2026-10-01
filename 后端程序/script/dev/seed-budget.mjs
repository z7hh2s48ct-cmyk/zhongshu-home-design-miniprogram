#!/usr/bin/env node
// P1-3（测试报告 15）：本地开发预算种子——地区 + 启用标准项/选项 + 发布黄金价。
// 全新环境 budget_region 为空表，小程序预算页地区下拉为空、生成永久禁用（开箱即断）。
// 幂等：已存在的地区/已发布价格自动跳过，可重复执行。
//
// 用法：后端以 local,pg,zsdev 启动后执行  node script/dev/seed-budget.mjs [地区代码=ZSDEV1]
const base = process.env.ZS_SEED_BASE_URL || 'http://127.0.0.1:48080';
const REGION = (process.argv[2] || 'ZSDEV1').toUpperCase();
const REGION_NAME = process.env.ZS_SEED_REGION_NAME || '本地联调地区';

const j = async (r) => { const x = await r.json(); if (x.code !== 0) throw new Error(`${x.code} ${x.msg}`); return x.data; };
let seq = 0;
const key = (n) => `seed-budget-${REGION}-${n}-${seq++}`;
const req = (method, path, body, token, idem) => fetch(base + path, {
  method,
  headers: {
    'Content-Type': 'application/json', 'tenant-id': '1',
    ...(token ? { Authorization: 'Bearer ' + token } : {}),
    ...(idem ? { 'Idempotency-Key': idem } : {}),
  },
  body: body === undefined ? undefined : JSON.stringify(body),
}).then(j);

// 13 项黄金价（分），与 e2e T10-17 口径一致
const GOLDEN_PRICES = [
  ['208001', 52000], ['208006', 98000], ['208009', 52000], ['208012', 12000], ['208015', 1800000],
  ['208020', 49000], ['208023', 12000], ['208026', 12000], ['208029', 12000], ['208030', 6000],
  ['208032', 8500], ['208034', 500000], ['208035', 500000],
];

(async () => {
  const login = await req('POST', '/admin-api/system/auth/login', { username: 'admin', password: 'admin123', captchaVerification: '' });
  const T = login.accessToken;

  // 1) 地区（存在即复用）
  const existing = await req('GET', '/admin-api/design/v1/budget/regions?pageNo=1&pageSize=100', undefined, T);
  let region = (existing.list || []).find(r => r.code === REGION);
  if (region) {
    console.log(`region ${REGION} 已存在（enabled=${region.enabled}），复用`);
    if (!region.enabled) {
      region = await req('PATCH', `/admin-api/design/v1/budget/regions/${region.regionId}`,
        { name: region.name, enabled: true, expectedVersion: region.version }, T, key('region-enable'));
    }
  } else {
    region = await req('POST', '/admin-api/design/v1/budget/regions', { code: REGION, name: REGION_NAME, enabled: true }, T, key('region'));
    console.log(`region ${REGION} 已创建：`, region.regionId);
  }

  // 2) 启用全部标准项 + 标准选项
  const items = await req('GET', '/admin-api/design/v1/budget/items?pageNo=1&pageSize=100', undefined, T);
  const standard = (items.list || []).filter(x => x.source === 'STANDARD');
  let changedItems = 0;
  for (const it of standard) {
    if (it.enabled && it.publicSelectable) continue;
    await req('PATCH', `/admin-api/design/v1/budget/items/${it.itemId}`,
      { name: it.name, enabled: true, publicSelectable: true, sortOrder: it.sortOrder, expectedVersion: it.version }, T, key('item' + it.itemId));
    changedItems++;
  }
  const options = await req('GET', '/admin-api/design/v1/budget/options?pageNo=1&pageSize=100', undefined, T);
  const stdIds = new Set(standard.map(x => x.itemId));
  const stdOpts = (options.list || []).filter(x => stdIds.has(x.itemId));
  let changedOpts = 0;
  for (const op of stdOpts) {
    if (op.enabled) continue;
    await req('PATCH', `/admin-api/design/v1/budget/options/${op.optionId}`,
      { label: op.label, selectionGroup: op.selectionGroup, unit: op.unit, quantitySource: op.quantitySource, quantityKey: op.quantityKey, sourceReference: op.sourceReference, enabled: true, sortOrder: op.sortOrder, expectedVersion: op.version }, T, key('opt' + op.optionId));
    changedOpts++;
  }
  console.log(`标准项 ${standard.length}（本次启用 ${changedItems}）、标准选项 ${stdOpts.length}（本次启用 ${changedOpts}）`);

  // 3) 发布缺失的黄金价（已发布同价跳过）
  let published = 0, skipped = 0;
  for (const [optionId, cents] of GOLDEN_PRICES) {
    const list = await req('GET', `/admin-api/design/v1/budget/prices?regionCode=${REGION}&optionId=${optionId}&status=PUBLISHED&pageNo=1&pageSize=20`, undefined, T);
    if ((list.list || []).some(p => p.unitPriceCents === cents)) { skipped++; continue; }
    const draft = await req('POST', '/admin-api/design/v1/budget/prices', { regionCode: REGION, optionId, unitPriceCents: cents, effectiveAt: '2020-01-01T00:00:00Z' }, T, key('price' + optionId));
    await req('POST', `/admin-api/design/v1/budget/prices/${draft.priceId}/publish`, { expectedVersion: draft.version, reason: '本地开发种子价（P1-3）' }, T, key('pub' + optionId));
    published++;
  }
  console.log(`黄金价：本次发布 ${published}，已存在跳过 ${skipped}（共 ${GOLDEN_PRICES.length}）`);
  console.log('SEED OK：小程序预算页刷新后地区「' + (region.name || REGION) + '」即可用');
})().catch(e => { console.error('SEED FAIL:', e.message); process.exit(1); });
