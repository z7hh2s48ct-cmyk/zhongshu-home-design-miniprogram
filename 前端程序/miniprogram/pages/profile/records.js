'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const view = require('../../utils/record-view');
const assets = require('../../utils/assets');

protectedPage({
  data: { title: '我的记录', rows: [], loading: false, error: '', hasMore: false, emptyText: '', canDelete: false, swipedId: '' },
  onLoad(options) {
    this._type = Object.prototype.hasOwnProperty.call(view.titles, options.type) ? options.type : null;
    if (!this._type) { this.setData({ error: '记录类型无效，请从「我的」重新进入' }); return; }
    this.setData({ title: view.titles[this._type], canDelete: this._type === 'projects',
      emptyText: ({ projects: '还没有方案，完成设计后会自动保留在这里', submissions: '还没有投稿，可从已完成的方案提交审核', favorites: '还没有收藏，去户型详情收藏喜欢的设计', orders: '还没有充值订单' })[this._type] });
  },
  onShow() { if (this._type) this.reload(); },
  onUnload() { this._seq = (this._seq || 0) + 1; this._token = null; },
  onReachBottom() { this.more(); },
  // 下拉刷新（UX 2026-10：对标同行，去掉页内刷新按钮）
  onPullDownRefresh() {
    Promise.resolve(this.reload()).catch(() => {}).then(() => wx.stopPullDownRefresh());
  },
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
      if (type === 'projects') this.loadCovers(rows);
    }).catch(error => { if (this.current(seq)) this.setData({ loading: false, error: view.errorText(error) }); });
  },
  // 封面缩略图：逐行经下载票据换 data URL；行序以 id 定位，避免并发回写错位
  loadCovers(rows) {
    const seq = this._seq;
    rows.forEach((row, index) => {
      if (!row.coverAssetId || row.coverUrl) return;
      assets.fetchAssetDataUrl(row.coverAssetId).then(url => {
        if (seq !== this._seq) return;
        const target = this.data.rows.find(item => item.id === row.id);
        if (target && target.coverAssetId === row.coverAssetId && !target.coverUrl) {
          this.setData({ ['rows[' + this.data.rows.indexOf(target) + '].coverUrl']: url });
        }
      }).catch(function () { /* 封面失败不阻断列表，行保持无图布局 */ });
    });
  },
  more() { if (this.data.hasMore) return this.load(); },
  retry() { return (http.isSameSession ? http.isSameSession(this._token) : this._token === http.getToken()) ? this.load() : this.reload(); },
  open(event) {
    const id = event.currentTarget.dataset.id;
    // 已滑开删除按钮的行：第一次点击先收起，不导航
    if (this.data.swipedId) {
      const wasOpen = this.data.swipedId === id;
      this.setData({ swipedId: '' });
      if (wasOpen) return;
    }
    if (!this.current(this._seq)) { this.setData({ rows: [], hasMore: false, error: '登录身份已变化，请重新进入' }); return; }
    const row = this.data.rows.find(item => item.id === id);
    if (row) wx.navigateTo({ url: row.url, fail: () => wx.showToast({ title: '打开失败，请重试', icon: 'none' }) });
  },
  // —— 滑动删除（UX 2026-10）：横向滑动露出删除按钮，长按直达确认框；仅「我的方案」开启 ——
  swipeStart(event) {
    const touch = event.touches && event.touches[0];
    this._swipe = { id: event.currentTarget.dataset.id, x: touch && touch.clientX, y: touch && touch.clientY };
  },
  swipeEnd(event) {
    if (!this._swipe || this._swipe.id !== event.currentTarget.dataset.id) return;
    const touch = event.changedTouches && event.changedTouches[0];
    const dx = (touch && touch.clientX) - this._swipe.x;
    const dy = (touch && touch.clientY) - this._swipe.y;
    const id = this._swipe.id;
    this._swipe = null;
    if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy) * 1.5) {
      this.setData({ swipedId: this.data.swipedId === id ? '' : id });
    } else if (this.data.swipedId && this.data.swipedId !== id && Math.abs(dx) < 10 && Math.abs(dy) < 10) {
      this.setData({ swipedId: '' });  // 点击其他行时收起已滑开的行
    }
  },
  confirmDelete(event) {
    if (this.data.canDelete === false || this._type !== 'projects') return;
    const id = event.currentTarget.dataset.id;
    const row = this.data.rows.find(item => item.id === id);
    if (!row) return;
    if (['QUEUED', 'RUNNING', 'VALIDATING', 'CANCEL_REQUESTED'].includes(row.jobStatus)) {
      wx.showToast({ title: '生成进行中，暂不能删除', icon: 'none' });
      return;
    }
    wx.showModal({
      title: '删除方案',
      content: '删除后「' + row.title + '」将从我的方案移除，不可恢复。',
      confirmText: '删除',
      confirmColor: '#b45545',
      success: (res) => { if (res.confirm) this.deleteRow(id); }
    });
  },
  deleteRow(id) {
    const seq = this._seq;
    api.deleteProject(id).then(() => {
      if (seq !== this._seq) return;
      this.setData({ rows: this.data.rows.filter(item => item.id !== id), swipedId: '' });
      wx.showToast({ title: '已删除', icon: 'success' });
    }).catch(error => {
      if (seq !== this._seq) return;
      this.setData({ swipedId: '' });
      wx.showToast({ title: view.errorText(error), icon: 'none' });
    });
  },
  // 封面缩略图点击放大（catchtap 阻断卡片打开）
  previewCover(event) {
    const url = event.currentTarget.dataset.url;
    if (url) assets.previewImages([url], url);
  }
});
