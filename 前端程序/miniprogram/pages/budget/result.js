'use strict';
const { protectedPage } = require('../../utils/access');
const { budgetPage, wan } = require('../../utils/budget-view');
const share = require('../../utils/budget-share');

// result 页在共用读取工厂之上扩展：转发卡片（决策 3-A）与预算图导出（决策 3-B）。
// 预算是私有数据：转发 path 落首页（告知/引流），对方无法查看他人预算。
const page = Object.assign(budgetPage(), {
  data: Object.assign({}, budgetPage().data, { exporting: false }),
  onShareAppMessage() {
    const estimate = this.data.estimate;
    if (!estimate || estimate.model !== 'ITEMIZED_V1' || estimate.totalCents == null) {
      return { title: '众墅之家 · AI乡墅设计', path: '/pages/home/index' };
    }
    return { title: '我的乡墅参考预算约' + wan(estimate.totalCents) + '万 · 众墅之家', path: '/pages/home/index' };
  },
  exportImage() { share.exportBudgetImage(this); }
});
protectedPage(page);
