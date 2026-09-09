'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const view = require('../../utils/record-view');
const format = require('../../utils/format');
protectedPage({
  data: { title: '方案记录', loading: false, error: '', fields: [], versions: [], projectId: '', versionId: '', loaded: false, canResubmit: false, resubmitNote: '', submitting: false, submitError: '' },
  onLoad(options) {
    this._type = ['projects', 'submissions'].includes(options.type) ? options.type : null;
    this._id = view.id(options.id);
    this.setData({ title: this._type === 'submissions' ? '投稿详情' : '方案记录' });
  },
  onShow() { this.load(); },
  onUnload() { this._seq = (this._seq || 0) + 1; this._token = null; },
  current(seq) {
    if (seq !== this._seq) return false;
    if (this._token && (http.isSameSession ? http.isSameSession(this._token) : this._token === http.getToken())) return true;
    this._resubmitCommand = null;
    this.setData({ fields: [], versions: [], projectId: '', versionId: '', loaded: false, loading: false, canResubmit: false, resubmitNote: '', submitting: false, error: '登录身份已变化，请重新进入' });
    return false;
  },
  load() {
    if (this.data.submitting) return;
    const seq = this._seq = (this._seq || 0) + 1; this._token = http.getToken();
    this.setData({ loading: false, error: '', fields: [], versions: [], projectId: '', versionId: '', loaded: false, canResubmit: false, canResume: false });
    if (!this._type || !this._id) { this.setData({ error: '记录编号无效，请从「我的」重新进入' }); return; }
    if (!this._token) { this.setData({ error: '请重新登录后读取记录' }); return; }
    this.setData({ loading: true });
    const req = this._type === 'projects' ? Promise.all([api.getProject(this._id), api.getResultVersions(this._id)]) : api.getSubmission(this._id);
    return req.then(response => {
      if (!this.current(seq)) return;
      if (this._type === 'projects') {
        const [project, page] = response;
        if (!project || project.projectId !== this._id || !page || !Array.isArray(page.list)) throw Error('方案记录不完整');
        const versions = page.list.map(item => {
          if (!view.id(item.versionId)) throw Error('方案版本编号无效');
          return { id: item.versionId, title: '方案 v' + item.version, hint: item.superseded ? '历史版本 · 只读回看' : '当前版本', time: format.shortTime(item.createdAt) };
        });
        this.setData({ projectId: project.projectId, versions,
          canResume: ['POLL_JOB', 'SELECT_FLAT', 'SELECT_ELEVATION', 'CREATE_ELEVATION_JOB', 'CREATE_FLAT_JOB'].includes(project.resumeAction), fields: [
          { label: '项目编号', value: project.projectId }, { label: '进度', value: versions.length ? '已形成 ' + versions.length + ' 个方案版本' : (project.stage === 'ELEVATION' ? '立面阶段，尚未选定最终方案' : '平面阶段，尚未形成最终方案') },
          { label: '来源', value: project.sourceType === 'CASE_REFERENCE' ? '基于户型库设计' : '自主设计' }
        ] });
      } else {
        if (!response || response.submissionId !== this._id || !view.id(response.projectId) || !view.id(response.resultVersionId)) throw Error('投稿记录不完整');
        this.setData({ projectId: response.projectId, versionId: response.resultVersionId,
          canResubmit: response.status === 'CHANGES_REQUESTED' && (response.allowedActions || []).includes('RESUBMIT'),
          resubmitNote: this._resubmitCommand ? this._resubmitCommand.note : (response.note || ''), fields: [
          { label: '投稿编号', value: response.submissionId }, { label: '审核状态', value: view.status(response.status) },
          { label: '发布状态', value: response.publicationStatus === 'PUBLISHED' ? '已发布到户型库' : response.publicationStatus === 'OFFLINE' ? '已从户型库下架' : '尚未发布（审核通过不等于已发布）' },
          { label: '审核轮次', value: String(response.currentRound || 1) }, { label: '提交时间', value: format.shortTime(response.submittedAt) || '—' },
          { label: '审核意见', value: response.reviewComment || '暂无审核意见' }
        ] });
      }
      this.setData({ loaded: true, loading: false });
    }).catch(error => { if (this.current(seq)) this.setData({ error: view.errorText(error), loading: false }); });
  },
  editResubmitNote(event) {
    if (this.data.submitting || !this.current(this._seq)) return;
    this.setData({ resubmitNote: String(event.detail.value || '').slice(0, 512), submitError: '' });
  },
  resubmit() {
    if (this.data.submitting || !this.data.canResubmit || !this.current(this._seq)) return;
    const note = this.data.resubmitNote.trim();
    if (!note) { this.setData({ submitError: '请填写修改说明' }); return; }
    // 未收到成功响应前保留同一请求键；重复点击与网络重试不会增加轮次。
    if (!this._resubmitCommand || this._resubmitCommand.note !== note) {
      this._resubmitCommand = { note, key: 'resubmit-' + this._id + '-' + Date.now() + '-' + Math.random().toString(36).slice(2) };
    }
    const command = this._resubmitCommand, seq = this._seq;
    this.setData({ submitting: true, submitError: '' });
    return api.resubmitSubmission(this._id, command.note, command.key).then(response => {
      if (!this.current(seq)) return;
      if (!response || response.submissionId !== this._id) throw Error('重提结果不完整，请重试');
      this._resubmitCommand = null;
      this.setData({ submitting: false, canResubmit: false });
      wx.showToast({ title: '已重新提交审核', icon: 'success' });
      return this.load();
    }).catch(error => { if (this.current(seq)) this.setData({ submitting: false, submitError: view.errorText(error) }); });
  },
  openVersion(event) {
    if (!this.current(this._seq)) return;
    const versionId = event && event.currentTarget.dataset.id || this.data.versionId;
    if (versionId !== this.data.versionId && !this.data.versions.some(item => item.id === versionId)) return;
    if (view.id(versionId)) this.navigate('/pages/ai-design/result?projectId=' + this.data.projectId + '&resultVersionId=' + versionId);
  },
  budgetHistory() { if (this.current(this._seq) && this.data.projectId) this.navigate('/pages/budget/history?projectId=' + this.data.projectId); },
  resumeDesign() {
    if (!this.current(this._seq) || !this.data.canResume || this._resuming) return;
    this._resuming = true;
    return require('../../utils/project-resume').resume(this.data.projectId)
      .catch(error => { if (!error.cancelled) wx.showToast({ title: view.errorText(error), icon: 'none' }); })
      .finally(() => { this._resuming = false; });
  },
  navigate(url) { wx.navigateTo({ url, fail: () => wx.showToast({ title: '页面打开失败，请重试', icon: 'none' }) }); }
});
