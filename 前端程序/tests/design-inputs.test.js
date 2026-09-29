const test = require('node:test');
const assert = require('node:assert/strict');
const designInputs = require('../miniprogram/utils/design-inputs');

test('户型设计模式：全量键且面宽进深为数字', () => {
  const built = designInputs.buildRequirementInputs(0, {
    faceWidth: '12.6', depth: '13.8',
    floorIndex: designInputs.indexOfFloor('两层'),
    familyIndex: designInputs.indexOfFamily('5室3厅2卫'),
    note: '老人房在一楼', prompt: '不应出现'
  });
  assert.ok(!built.error, built.error);
  assert.deepEqual(built.inputs, {
    faceWidthM: 12.6, depthM: 13.8,
    floor: '两层', floorCount: 2,
    family: '5室3厅2卫', rooms: { bedroom: 5, living: 3, bath: 2 },
    note: '老人房在一楼'
  });
});

test('自主设计模式：带 prompt 不带尺寸', () => {
  const built = designInputs.buildRequirementInputs(1, {
    faceWidth: '', depth: '',
    floorIndex: 0, familyIndex: 0,
    note: '', prompt: '宅基地面宽12米，两层'
  });
  assert.ok(!built.error, built.error);
  assert.deepEqual(built.inputs, {
    floor: '一层', floorCount: 1,
    family: '2室1厅1卫', rooms: { bedroom: 2, living: 1, bath: 1 },
    note: '', prompt: '宅基地面宽12米，两层'
  });
});

test('面宽进深越界或格式非法被拒绝', () => {
  for (const bad of ['2.9', '40.1', 'abc', '', '-5', '12.65', '0']) {
    const built = designInputs.buildRequirementInputs(0, {
      faceWidth: bad, depth: '13.8', floorIndex: 1, familyIndex: 4, note: '', prompt: ''
    });
    assert.ok(built.error, `面宽 ${bad} 应被拒绝`);
  }
  const built = designInputs.buildRequirementInputs(0, {
    faceWidth: '12.6', depth: '3', floorIndex: 1, familyIndex: 4, note: '', prompt: ''
  });
  assert.ok(!built.error, built.error);
});

test('参考案例尺寸预填映射', () => {
  assert.equal(designInputs.indexOfFloor('两层'), 1);
  const index = designInputs.FLOOR_OPTIONS.findIndex((o) => o.count === 2);
  assert.equal(index, 1);
  assert.equal(designInputs.indexOfFamily('未知户型'), 4, '未知值回退默认 5室3厅2卫');
});

test('选择器标签与选项一致', () => {
  assert.deepEqual(designInputs.floorLabels(), ['一层', '两层', '三层', '四层']);
  assert.equal(designInputs.familyLabels().length, designInputs.FAMILY_OPTIONS.length);
  for (const option of designInputs.FAMILY_OPTIONS) {
    assert.match(option.label, /^\d室\d厅\d卫$/);
    const parsed = option.label.match(/^(\d)室(\d)厅(\d)卫$/);
    assert.equal(option.rooms.bedroom, Number(parsed[1]));
    assert.equal(option.rooms.living, Number(parsed[2]));
    assert.equal(option.rooms.bath, Number(parsed[3]));
  }
});
