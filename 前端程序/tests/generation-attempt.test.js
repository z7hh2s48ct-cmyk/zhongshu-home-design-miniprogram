const test = require('node:test');
const assert = require('node:assert/strict');
const loadAttempt = require('./helpers/generation-attempt');
test('ambiguous creation keeps key, confirmed price and project across page reload', () => {
  const storage = new Map(), first = loadAttempt(storage);
  const pending = first.begin('initial', { count: 2, body: { sketchAssetId: '601', sourceType: 'SELF_UPLOAD' } });
  pending.projectId = '501'; pending.confirmedPrice = { ruleId: '31', ruleVersion: 1 }; first.save('initial', pending);
  const resumed = loadAttempt(storage).begin('initial', { body: { sourceType: 'SELF_UPLOAD', sketchAssetId: '601' }, count: 2 });
  assert.equal(resumed.key, pending.key); assert.equal(resumed.projectId, '501'); assert.equal(resumed.confirmedPrice.ruleId, '31');
  assert.throws(() => first.begin('initial', { count: 4 }), error => /尚未确认/.test(error.msg));
});
test('account switch isolates pending intentions and rejects stale asynchronous writes', () => {
  const storage = new Map(), session = { scope: 'account-a' }, attempts = loadAttempt(storage, session);
  const old = attempts.begin('initial', { count: 1 }); session.scope = 'account-b';
  assert.equal(attempts.read('initial'), null);
  assert.throws(() => attempts.save('initial', old), error => error.code === 'SESSION_CHANGED');
  assert.throws(() => attempts.complete('initial', old), error => error.code === 'SESSION_CHANGED');
});
test('a confirmed operation is cleared so an explicit new batch obtains a new key', () => {
  const attempts = loadAttempt(), old = attempts.begin('initial', { count: 1 }); attempts.complete('initial', old);
  assert.notEqual(attempts.begin('initial', { count: 1 }).key, old.key);
});

test('unsubmitted cancellation clears intent, explicit price rejection requotes same key, ambiguous errors retain quote', () => {
  const attempts = require('./helpers/generation-attempt')();
  const original=attempts.begin('fixture',{width:12});assert.equal(attempts.failed('fixture',original,{cancelled:true}),true);
  const edited=attempts.begin('fixture',{width:18});edited.confirmedPrice={ruleVersion:1};attempts.submitted('fixture',edited);
  attempts.failed('fixture',edited,{msg:'timeout'});assert.equal(attempts.read('fixture').confirmedPrice.ruleVersion,1);
  assert.equal(attempts.failed('fixture',edited,{cancelled:true}),false);
  attempts.failed('fixture',edited,{code:1072000001});const retry=attempts.begin('fixture',{width:18});
  assert.equal(retry.key,edited.key);assert.equal(retry.confirmedPrice,undefined);assert.equal(retry.state,'REJECTED_PRICE');
});
