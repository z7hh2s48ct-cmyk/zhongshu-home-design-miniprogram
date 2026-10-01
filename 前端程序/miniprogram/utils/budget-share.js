'use strict';

// 预算图导出（2026-09-29 决策 3-B）：Canvas 2D 绘制完整预算摘要卡并保存到相册。
// 口径：仅完整预算（COMPLETE）允许导出；金额一律按万元；
// 码位读取 assets/v12/miniprogram-qr.png——2026-10-02 已投放公众平台下载的真实小程序码（344×344），
// 资源缺失时仍画品牌徽标兜底，绝不绘制假码。
const view = require('./budget-view');

const QR_PATH = '/assets/v12/miniprogram-qr.png';
const W = 750;
const H = 1200;

function truncate(text, max) {
  const value = String(text == null ? '' : text);
  return value.length > max ? value.slice(0, max) + '…' : value;
}

function exportBudgetImage(page) {
  const estimate = page.data && page.data.estimate;
  if (!estimate || estimate.model !== 'ITEMIZED_V1' || estimate.completeness !== 'COMPLETE' || estimate.totalCents == null) {
    wx.showToast({ title: '预算补齐后才能导出图片', icon: 'none' });
    return;
  }
  if (page.data.exporting) return;
  page.setData({ exporting: true });
  const query = wx.createSelectorQuery().in(page);
  query.select('#budgetCanvas').fields({ node: true }).exec(res => {
    if (!res || !res[0] || !res[0].node) {
      page.setData({ exporting: false });
      wx.showToast({ title: '导出组件未就绪，请重试', icon: 'none' });
      return;
    }
    draw(res[0].node, estimate, page);
  });
}

function draw(canvas, estimate, page) {
  const dpr = 2;
  canvas.width = W * dpr;
  canvas.height = H * dpr;
  const ctx = canvas.getContext('2d');
  ctx.scale(dpr, dpr);

  ctx.fillStyle = '#faf9f7';
  ctx.fillRect(0, 0, W, H);

  // 品牌区
  ctx.fillStyle = '#7c3f16';
  ctx.font = 'bold 46px sans-serif';
  ctx.fillText('众墅之家', 60, 108);
  ctx.fillStyle = '#a08760';
  ctx.font = '26px sans-serif';
  ctx.fillText('AI 乡墅设计 · 参考预算', 60, 152);
  ctx.strokeStyle = '#e6e7e9';
  ctx.lineWidth = 2;
  ctx.beginPath(); ctx.moveTo(60, 185); ctx.lineTo(W - 60, 185); ctx.stroke();

  // 项目 / 方案
  const name = (estimate.projectName || '乡墅方案') + (estimate.schemeName ? ' · ' + estimate.schemeName : '');
  ctx.fillStyle = '#121314';
  ctx.font = '30px sans-serif';
  ctx.fillText(truncate(name, 20), 60, 240);

  // 总价（万元）
  ctx.fillStyle = '#603009';
  ctx.font = 'bold 118px sans-serif';
  ctx.fillText(view.wan(estimate.totalCents), 60, 392);
  ctx.font = '30px sans-serif';
  ctx.fillStyle = '#8a4617';
  ctx.fillText('万元 · 参考总价', 60, 442);

  // 构成两块
  const body = view.amount(estimate.categoryTotals.BODY, false);
  const exterior = view.amount(estimate.categoryTotals.EXTERIOR, false);
  ctx.fillStyle = '#fff';
  roundRect(ctx, 60, 490, 300, 150, 20); ctx.fill();
  roundRect(ctx, 390, 490, 300, 150, 20); ctx.fill();
  ctx.fillStyle = '#52565f'; ctx.font = '26px sans-serif';
  ctx.fillText('主体类', 92, 540); ctx.fillText('外装类', 422, 540);
  ctx.fillStyle = '#603009'; ctx.font = 'bold 44px sans-serif';
  ctx.fillText(body, 92, 606); ctx.fillText(exterior, 422, 606);

  // 参数摘要
  const s = estimate.inputSummary || {};
  const params = [
    '占地面积 ' + (s.footprintArea ? s.footprintArea + '㎡' : '—'),
    '建筑层数 ' + (s.floorCount ? s.floorCount + '层' : '—'),
    '屋顶面积 ' + (s.roofArea ? s.roofArea + '㎡' : '—'),
    '建造地区 ' + (estimate.regionName || '—')
  ];
  ctx.fillStyle = '#52565f';
  ctx.font = '26px sans-serif';
  params.forEach((line, index) => ctx.fillText(line, 60, 710 + index * 48));

  // 免责声明 + 时间（入图合规文案）
  ctx.strokeStyle = '#e6e7e9';
  ctx.beginPath(); ctx.moveTo(60, 940); ctx.lineTo(W - 60, 940); ctx.stroke();
  ctx.fillStyle = '#98938c';
  ctx.font = '24px sans-serif';
  ctx.fillText(truncate(estimate.disclaimer || '本预算为前期参考，最终金额以正式报价单为准', 30), 60, 984);
  ctx.fillText('生成时间 ' + truncate(estimate.createdAt || '', 19), 60, 1020);

  // 码位：真实码文件存在则绘制，否则品牌徽标兜底
  const qr = canvas.createImage();
  const done = () => finish(canvas, page);
  qr.onload = () => {
    ctx.drawImage(qr, W - 230, H - 250, 160, 160);
    ctx.fillStyle = '#98938c';
    ctx.font = '20px sans-serif';
    ctx.fillText('长按识别码进入', W - 242, H - 62);
    done();
  };
  qr.onerror = () => {
    drawBadge(ctx, W - 230, H - 250, 160);
    done();
  };
  qr.src = QR_PATH;
}

function roundRect(ctx, x, y, width, height, radius) {
  ctx.beginPath();
  ctx.moveTo(x + radius, y);
  ctx.arcTo(x + width, y, x + width, y + height, radius);
  ctx.arcTo(x + width, y + height, x, y + height, radius);
  ctx.arcTo(x, y + height, x, y, radius);
  ctx.arcTo(x, y, x + width, y, radius);
  ctx.closePath();
}

function drawBadge(ctx, x, y, size) {
  ctx.beginPath();
  ctx.arc(x + size / 2, y + size / 2, size / 2, 0, Math.PI * 2);
  ctx.fillStyle = '#7c3f16';
  ctx.fill();
  ctx.fillStyle = '#fff';
  ctx.font = 'bold 30px sans-serif';
  ctx.textAlign = 'center';
  ctx.fillText('众墅', x + size / 2, y + size / 2 - 4);
  ctx.fillText('之家', x + size / 2, y + size / 2 + 34);
  ctx.textAlign = 'left';
}

function finish(canvas, page) {
  wx.canvasToTempFilePath({
    canvas,
    success: res => {
      wx.saveImageToPhotosAlbum({
        filePath: res.tempFilePath,
        success: () => {
          page.setData({ exporting: false });
          wx.showToast({ title: '已保存到相册', icon: 'success' });
        },
        fail: error => {
          page.setData({ exporting: false });
          if (error && /auth/i.test(error.errMsg || '')) promptAlbumPermission();
          else wx.showToast({ title: '保存失败，请重试', icon: 'none' });
        }
      });
    },
    fail: () => {
      page.setData({ exporting: false });
      wx.showToast({ title: '导出失败，请重试', icon: 'none' });
    }
  });
}

function promptAlbumPermission() {
  wx.showModal({
    title: '需要相册权限',
    content: '保存预算图需要「添加到相册」权限，请在设置中开启。',
    confirmText: '去设置',
    success: result => {
      if (result.confirm) wx.openSetting({});
    }
  });
}

module.exports = { exportBudgetImage, QR_PATH, truncate };
