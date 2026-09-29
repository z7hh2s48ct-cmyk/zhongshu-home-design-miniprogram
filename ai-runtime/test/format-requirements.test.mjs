import test from 'node:test';
import assert from 'node:assert/strict';
import { formatRequirements } from '../src/runtime.mjs';

test('T15 新键格式化为中文需求描述', () => {
  const text = formatRequirements({
    faceWidthM: '12.6', depthM: '13.8', floor: '两层', floorCount: 2,
    family: '5室3厅2卫', rooms: { bedroom: 5, living: 3, bath: 2 },
    prompt: '', note: '老人房在一楼，保留露台'
  });
  assert.equal(text, '宅基地面宽12.6米、进深13.8米；两层；5室3厅2卫；补充需求：老人房在一楼，保留露台');
});

test('立面配置键翻译为中文材质风格词', () => {
  const text = formatRequirements({
    styleCode: 'NEW_CHINESE', roofType: 'GABLE_ROOF', material: 'GREY_STONE', color: 'DEEP_WOOD'
  });
  assert.equal(text, '新中式风格；坡屋顶；灰色石材外墙；深木色点缀');
});

test('缺 family 时用 rooms 结构化值；未知键原样附带；budgetInputs 不进提示词', () => {
  const text = formatRequirements({
    floorCount: 3, rooms: { bedroom: 4, living: 2, bath: 1, kitchen: 1 },
    budgetInputs: { regionCode: 'VAR1' }, floors: 2
  });
  assert.ok(text.startsWith('3层；4室2厅1卫'), text);
  assert.ok(text.includes('其他需求数据：{"floors":2}'), text);
  assert.ok(!text.includes('budgetInputs'), text);
});

test('空需求与 null 兼容旧快照', () => {
  assert.equal(formatRequirements(null), 'null');
  assert.equal(formatRequirements({}), '{}');
  assert.equal(formatRequirements({ width: 10 }), '其他需求数据：{"width":10}');
});
