'use strict';
const api = require('./api');
const view = require('./record-view');
const generationOptions = require('./generation-options');

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
  const http = require('./request');
  const session = http.captureSession();
  const assertSession = () => {
    if (!http.isSameSession(session)) throw { code: 'SESSION_CHANGED', msg: '登录账号已变化，请重新进入' };
  };
  return api.getProject(projectId).then(async project => {
    assertSession();
    const url = target(project);
    if (!url) throw { msg: '请从 AI 设计页开始新的平面生成' };
    const global = getApp().globalData;
    const attempts = require('./generation-attempt');
    const initial = attempts.read('initial-flat');
    const matchesInitial = attempt => attempt && (String(attempt.projectId) === String(project.projectId)
      || (project.initialGenerationKey && attempt.key === project.initialGenerationKey));
    if (matchesInitial(initial) && project.jobId) attempts.complete('initial-flat', initial);
    Object.assign(global, { projectId: project.projectId, jobId: project.jobId || null,
      selectedFlatAssetId: project.selectedFlatAssetId || null, selectedElevationAssetId: null,
      resultVersionId: project.resultVersionId || null, elevationConfig: null, flatLabel: '已选平面方案' });
    if (project.resumeAction === 'CREATE_FLAT_JOB') {
      const attempts = require('./generation-attempt');
      const initial = attempts.read('initial-flat');
      const sharedInitial = matchesInitial(initial);
      const count = sharedInitial ? initial.payload.count : project.requestedCount || 2;
      const selected = generationOptions.selection('FLAT', sharedInitial ? initial.payload.imageOptions : project);
      const slot = sharedInitial ? 'initial-flat' : 'resume-flat:' + project.projectId;
      const attempt = sharedInitial ? initial : attempts.begin(slot, { projectId: project.projectId, count, resolution: selected.resolution, orientation: selected.orientation });
      if (sharedInitial) { attempt.projectId = project.projectId; attempts.save(slot, attempt); }
      if (!sharedInitial && !project.jobId && project.initialGenerationKey) {
        attempt.key = project.initialGenerationKey; attempts.save(slot, attempt);
      }
      try {
        const price = attempt.confirmedPrice || await require('./generation-price').confirm('FLAT', count, '', { resolution: selected.resolution });
        assertSession(); attempts.assertCurrent(attempt);
        attempt.confirmedPrice = price; attempts.save(slot, attempt);
        attempts.submitted(slot, attempt);
        const job = await api.createFlatJob(project.projectId, count, attempt.key, price,
          { resolution: selected.resolution, orientation: selected.orientation });
        assertSession(); attempts.assertCurrent(attempt);
        attempts.complete(slot, attempt);
        global.jobId = job.jobId;
      } catch (error) {
        // A definitive price rejection permits re-confirming the same operation. Ambiguous
        // delivery keeps the original quote/key; changing account never edits the new scope.
        if (http.isSameSession(session)) attempts.failed(slot, attempt, error);
        throw error;
      }
    }
    assertSession();
    wx.navigateTo({ url });
  });
}
module.exports = { target, resume };
