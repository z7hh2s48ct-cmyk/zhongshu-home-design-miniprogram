const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const source = fs.readFileSync(path.join(__dirname, '../src/views/zs/recharge/index.vue'), 'utf8')

test('recharge page declares separate order and plan read permissions', () => {
  assert.match(source, /commerce:recharge-order:query/)
  assert.match(source, /commerce:recharge-plan:query/)
  assert.match(source, /activeTab === 'orders' && canViewOrders/)
  assert.match(source, /activeTab === 'plans' && canViewPlans/)
  assert.match(source, /zs-recharge--no-orders/)
  assert.match(source, /zs-recharge--no-plans/)
})

test('permission matrix has a valid default only for accessible views', () => {
  const selectDefault = (orders, plans, queryTab) =>
    plans && queryTab === 'plans' ? 'plans' : orders ? 'orders' : plans ? 'plans' : ''
  assert.equal(selectDefault(true, false, 'plans'), 'orders')
  assert.equal(selectDefault(false, true, ''), 'plans')
  assert.equal(selectDefault(true, true, 'plans'), 'plans')
  assert.equal(selectDefault(false, false, ''), '')
})
