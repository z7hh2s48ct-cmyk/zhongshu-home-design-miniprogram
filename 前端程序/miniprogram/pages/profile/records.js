'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const view = require('../../utils/record-view');

protectedPage({
  data: { title: '我的记录', rows: [], loading: false, error: '', hasMore: false, emptyText: '' },
  onLoad(options) {
    this._type = Object.prototype.hasOwnProperty.call(view.titles, options.type) ? options.type : null;
    if (!this._type) { this.setData({ error: '记录类型无效，请从「我的」重新进入' }); return; }
    this.setData({ title: view.titles[this._type], emptyText: ({ projects: '还没有方案，完成设计后会自动保留在这里', submissions: '还没有投稿，可从已完成的方案提交审核', favorites: '还没有收藏，去户型详情收藏喜欢的设计', orders: '还没有充值订单' })[this._type] });
  },
  onShow() { if (this._type) this.reload(); },
  onUnload() { this._seq = (this._seq || 0) + 1; this._token = null; },
  onReachBottom() { this.more(); },
  current(seq) {
    if (seq !== this._seq) return false;
    if (this._token && (http.isSameSession ? http.isSameSession(this._token) : this._token === http.getToken())) return true;
    this.setData({ rows: [], loading: false, hasMore: false, error: '登录身份已变化，请重新进入' });
    return false;
  },
  reload() {
    this._seq = (this._seq || 0) + 1; this._token = http.getToken(); this._cursor = null; this._page = 1;
    this.setData({ rows: [], error: '', loading: false, hasMore: false });
    return this.load();
  },
  load() {
    if (!this._type || this.data.loading) return;
    if (!this.current(this._seq)) { this.setData({ rows: [], hasMore: false, error: '登录身份已变化，请重新进入' }); return; }
    const seq = this._seq, cursor = this._cursor, pageNo = this._page, type = this._type;
    this.setData({ loading: true, error: '' });
    const request = type === 'orders' ? api.listRechargeOrders(pageNo, 20)
      : ({ projects: api.listProjects, submissions: api.listSubmissions, favorites: api.listFavorites })[type](cursor, 20);
    return request.then(response => {
      if (!this.current(seq)) return;
      if (!response || !Array.isArray(response.list)) throw Error('记录数据不完整，请重试');
      if (type !== 'orders' && response.nextCursor != null && (!view.id(response.nextCursor) || response.nextCursor === cursor)) throw Error('分页数据异常，请刷新');
      if (type === 'orders' && (!Number.isSafeInteger(response.total) || response.total < 0)) throw Error('订单分页数据异常');
      const incoming = response.list.map(item => view.row(type, item));
      const seen = new Set(this.data.rows.map(item => item.id));
      const rows = this.data.rows.concat(incoming.filter(item => { if (seen.has(item.id)) return false; seen.add(item.id); return true; }));
      const more = type === 'orders' ? pageNo * 20 < response.total : !!response.nextCursor;
      if (more && rows.length === this.data.rows.length) throw Error('分页未返回新的记录，请刷新');
      this._cursor = response.nextCursor || null; this._page = pageNo + 1;
      this.setData({ rows, hasMore: more, loading: false });
    }).catch(error => { if (this.current(seq)) this.setData({ loading: false, error: view.errorText(error) }); });
  },
  more() { if (this.data.hasMore) return this.load(); },
  retry() { return (http.isSameSession ? http.isSameSession(this._token) : this._token === http.getToken()) ? this.load() : this.reload(); },
  open(event) {
    if (!this.current(this._seq)) { this.setData({ rows: [], hasMore: false, error: '登录身份已变化，请重新进入' }); return; }
    const row = this.data.rows.find(item => item.id === event.currentTarget.dataset.id);
    if (row) wx.navigateTo({ url: row.url, fail: () => wx.showToast({ title: '打开失败，请重试', icon: 'none' }) });
  }
});
