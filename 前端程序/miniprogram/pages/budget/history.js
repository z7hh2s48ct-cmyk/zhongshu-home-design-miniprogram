'use strict';
const api = require('../../utils/api');
const draft = require('../../utils/budget-draft');
const view = require('../../utils/budget-view');
const format = require('../../utils/format');
const { protectedPage } = require('../../utils/access');

protectedPage({
  data: { rows: [], loading: false, error: '', savedOnly: true, nextCursor: null, noProject: false },
  onLoad(options) {
    this._projectId = draft.id(options.projectId);
    // P3-8（报告 15）：无项目上下文直达时不再显示无法自愈的报错，改为引导空态
    if (!this._projectId) { this.setData({ noProject: true }); return; }
    this.reload();
  },
  goMyProjects() { wx.navigateTo({ url: '/pages/profile/records?type=projects', fail: () => wx.switchTab({ url: '/pages/profile/index' }) }); },
  goAiDesign() { wx.switchTab({ url: '/pages/ai-design/index' }); },
  onShow() { if (this._shown || (this._scope && this._scope !== draft.sessionScope())) this.reload(); this._shown = true; },
  onUnload() { this._sequence = (this._sequence || 0) + 1; this._scope = null; },
  reload() {
    this._sequence = (this._sequence || 0) + 1;
    this._scope = draft.sessionScope();
    this.setData({ rows: [], loading: false, error: '', nextCursor: null });
    this.load();
  },
  filter(event) {
    const savedOnly = event.currentTarget.dataset.saved === 'true';
    if (savedOnly !== this.data.savedOnly) { this.setData({ savedOnly }); this.reload(); }
  },
  current() {
    if (!this._scope || this._scope !== draft.sessionScope()) {
      this._sequence = (this._sequence || 0) + 1;
      this.setData({ rows: [], loading: false, error: '登录身份已变化，请重新读取预算', nextCursor: null });
      return false;
    }
    return true;
  },
  load() {
    if (!this._projectId || this.data.loading || !this.current()) return;
    const seq = this._sequence, cursor = this.data.nextCursor;
    this.setData({ loading: true, error: '' });
    api.getBudgetHistory(this._projectId, { savedOnly: this.data.savedOnly, cursor, limit: 20 }).then(response => {
      if (seq !== this._sequence || !this.current()) return;
      if (!response || !Array.isArray(response.list) || (response.nextCursor != null && !draft.id(response.nextCursor))) throw Error('历史预算数据不完整');
      if (cursor && response.nextCursor === cursor) throw Error('分页游标未前进，请重新读取');
      const incoming = response.list.map(raw => {
        const estimate = view.publicBudget(raw);
        if (estimate.projectId !== this._projectId || (this.data.savedOnly && !estimate.saved)) throw Error('历史预算项目或保存状态不匹配');
        return { budgetId: estimate.budgetId, revisionId: estimate.revisionId || null, name: estimate.schemeName || estimate.projectName,
          time: format.shortTime(estimate.createdAt), saved: estimate.saved, legacy: estimate.model === 'LEGACY_RANGE',
          incomplete: estimate.completeness === 'INCOMPLETE', amountText: estimate.model === 'LEGACY_RANGE'
            ? view.wan(estimate.totalMinCents) + '–' + view.wan(estimate.totalMaxCents) + '万'
            : view.amount(estimate.totalCents == null ? estimate.pricedSubtotalCents : estimate.totalCents, false) };
      });
      const seen = new Set(this.data.rows.map(row => row.budgetId));
      const rows = this.data.rows.concat(incoming.filter(row => { if (seen.has(row.budgetId)) return false; seen.add(row.budgetId); return true; }));
      this.setData({ rows, loading: false, nextCursor: response.nextCursor || null });
    }).catch(error => { if (seq === this._sequence && this.current()) this.setData({ loading: false, error: view.errorText(error) }); });
  },
  more() { if (this.data.nextCursor) this.load(); },
  retry() { if (this._scope !== draft.sessionScope()) this.reload(); else this.load(); },
  open(event) {
    if (!this.current()) return;
    const row = this.data.rows.find(value => value.budgetId === event.currentTarget.dataset.id);
    if (row) wx.navigateTo({ url: '/pages/budget/result?budgetId=' + row.budgetId + (row.revisionId ? '&revisionId=' + row.revisionId : ''),
      fail: () => wx.showToast({ title: '预算打开失败，请重试', icon: 'none' }) });
  }
});
