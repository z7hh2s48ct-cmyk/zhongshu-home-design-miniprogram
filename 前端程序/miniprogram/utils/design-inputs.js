'use strict';

/**
 * T15：AI 设计页需求输入的构造与校验（纯函数，供页面与单测共用）。
 * 键与后端 BudgetInputs 白名单一一对应：未知键会被后端拒绝（1_071_000_005）。
 */

var FLOOR_OPTIONS = [
  { label: '一层', count: 1 },
  { label: '两层', count: 2 },
  { label: '三层', count: 3 },
  { label: '四层', count: 4 }
];

var FAMILY_OPTIONS = [
  { label: '2室1厅1卫', rooms: { bedroom: 2, living: 1, bath: 1 } },
  { label: '3室2厅1卫', rooms: { bedroom: 3, living: 2, bath: 1 } },
  { label: '3室2厅2卫', rooms: { bedroom: 3, living: 2, bath: 2 } },
  { label: '4室2厅2卫', rooms: { bedroom: 4, living: 2, bath: 2 } },
  { label: '5室3厅2卫', rooms: { bedroom: 5, living: 3, bath: 2 } },
  { label: '6室3厅3卫', rooms: { bedroom: 6, living: 3, bath: 3 } }
];

function floorLabels() { return FLOOR_OPTIONS.map(function (o) { return o.label; }); }
function familyLabels() { return FAMILY_OPTIONS.map(function (o) { return o.label; }); }

/** 面宽/进深：3~40 米，最多一位小数（与后端上限一致） */
function isValidLength(value) {
  if (typeof value !== 'string' || !/^\d{1,2}(\.\d)?$/.test(value)) return false;
  var n = Number(value);
  return n >= 3 && n <= 40;
}

function indexOfFloor(label) {
  var index = FLOOR_OPTIONS.findIndex(function (o) { return o.label === label; });
  return index < 0 ? 1 : index;
}

function indexOfFamily(label) {
  var index = FAMILY_OPTIONS.findIndex(function (o) { return o.label === label; });
  return index < 0 ? 4 : index;
}

/**
 * @param {number} mode 0=户型设计 1=自主设计
 * @param {{faceWidth:string, depth:string, floorIndex:number, familyIndex:number, note:string, prompt:string}} data
 * @returns {{inputs: Object}|{error: string}}
 */
function buildRequirementInputs(mode, data) {
  var faceWidth = String(data.faceWidth == null ? '' : data.faceWidth).trim();
  var depth = String(data.depth == null ? '' : data.depth).trim();
  if (mode === 0) {
    if (!isValidLength(faceWidth)) return { error: '面宽需在 3~40 米，可带一位小数' };
    if (!isValidLength(depth)) return { error: '进深需在 3~40 米，可带一位小数' };
  }
  var floor = FLOOR_OPTIONS[data.floorIndex] || FLOOR_OPTIONS[1];
  var family = FAMILY_OPTIONS[data.familyIndex] || FAMILY_OPTIONS[4];
  var inputs = {
    floor: floor.label,
    floorCount: floor.count,
    family: family.label,
    rooms: family.rooms,
    note: String(data.note == null ? '' : data.note)
  };
  if (mode === 0) {
    inputs.faceWidthM = Number(faceWidth);
    inputs.depthM = Number(depth);
  } else {
    inputs.prompt = String(data.prompt == null ? '' : data.prompt);
  }
  return { inputs: inputs };
}

module.exports = {
  FLOOR_OPTIONS: FLOOR_OPTIONS,
  FAMILY_OPTIONS: FAMILY_OPTIONS,
  floorLabels: floorLabels,
  familyLabels: familyLabels,
  isValidLength: isValidLength,
  indexOfFloor: indexOfFloor,
  indexOfFamily: indexOfFamily,
  buildRequirementInputs: buildRequirementInputs
};
