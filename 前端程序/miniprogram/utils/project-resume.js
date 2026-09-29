'use strict';
const api = require('./api');
const view = require('./record-view');

function target(project) {
  const id = view.id(project && project.projectId);
  if (!id) throw { msg: '项目编号无效' };
  const stage = project.stage === 'ELEVATION' ? 'elevation' : 'plane';
  switch (project.resumeAction) {
    case 'POLL_JOB':
      if (!view.id(project.jobId)) throw { msg: '任务编号无效' };
      return '/pages/ai-design/generating?stage=' + stage + '&count=' + (project.requestedCount || 2);
    case 'SELECT_FLAT':
    case 'SELECT_ELEVATION':
      // 选择动作已收敛到生成页内联候选：恢复态与主路径同一体验（jobId 已随 globalData 重建）
      return '/pages/ai-design/generating?stage=' + stage + '&count=' + (project.requestedCount || 2);
    case 'CREATE_ELEVATION_JOB': return '/pages/ai-design/elevation-setup';
    case 'CREATE_FLAT_JOB': return '/pages/ai-design/generating?stage=plane&count=' + (project.requestedCount || 2);
    case 'VIEW_RESULT':
      if (!view.id(project.resultVersionId)) throw { msg: '结果版本无效' };
      return '/pages/ai-design/result?projectId=' + id + '&resultVersionId=' + project.resultVersionId;
    default: return null;
  }
}

function resume(projectId) {
  return api.getProject(projectId).then(async project => {
    const url = target(project);
    if (!url) throw { msg: '请从 AI 设计页开始新的平面生成' };
    const global = getApp().globalData;
    Object.assign(global, { projectId: project.projectId, jobId: project.jobId || null,
      selectedFlatAssetId: project.selectedFlatAssetId || null, selectedElevationAssetId: null,
      resultVersionId: project.resultVersionId || null, elevationConfig: null, flatLabel: '已选平面方案' });
    if (project.resumeAction === 'CREATE_FLAT_JOB') {
      const count = project.requestedCount || 2;
      const price = await require('./generation-price').confirm('FLAT', count);
      const job = await api.createFlatJob(project.projectId, count, 'resume-' + Date.now() + '-' + Math.random().toString(36).slice(2), price);
      global.jobId = job.jobId;
    }
    wx.navigateTo({ url });
  });
}
module.exports = { target, resume };
