const { returnHome, refreshFromServer } = require('./utils/access');
const privacy = require('./utils/privacy');

// 场景值来源：微信官方《场景值列表》。此处只登记"用户手持明确目标"的场景——
// 分享卡片、扫码/识码、订阅消息直达。未登记的场景（含微信新增的未知场景）一律
// 维持原有行为（回首页），避免误放行而破坏激活门禁。
const DEEP_LINK_SCENES = new Set([
  1007, 1008, 1036, 1044, // 单聊/群聊/App/带 shareTicket 的分享卡片
  1011, 1012, 1013, 1025, // 扫二维码、长按识码、相册识码、扫一维码
  1047, 1048, 1049,       // 扫小程序码、长按识小程序码、相册小程序码
  1014, 1043,             // 订阅消息、公众号模板消息
  1035, 1074, 1053        // 公众号自定义菜单、公众号文章、视频号
]);

App({
  homeSeen: false,
  // 冷启动入口是否携带明确直达意图（分享/扫码/订阅消息等）
  deepLinkEntry: false,
  // 入口判定只在本次启动的首次 onShow 生效，避免从后台回前台时被二次拦截
  entryResolved: false,
  globalData: {
    projectId: null, jobId: null,
    selectedCandidateId: null, selectedElevationId: null,
    selectedFlatAssetId: null, selectedElevationAssetId: null,
    flatLabel: '', elevationLabel: '', resultVersionId: null,
    elevationConfig: null, refCase: null, budgetEstimate: null,
    unreadCount: 0,
    privacyAuthorized: true
  },
  onLaunch() {
    // 隐私合规：启动即探测一次。已同意或无需授权时不做任何打扰；
    // 需要授权时由微信原生弹窗接管（app.json 已开启 __usePrivacyCheck__）。
    // ensure() 内部不抛错、不 reject，拒绝授权也不会阻断后续流程。
    const self = this;
    privacy.ensure().then(function (authorized) {
      self.globalData.privacyAuthorized = authorized;
    });
  },
  onShow(options) {
    const entry = options || {};
    if (!this.entryResolved) {
      this.entryResolved = true;
      // 分享卡片/扫码/订阅消息等场景代表用户已有明确目标，必须保留目标页，
      // 否则分享与扫码链路会被无条件弹回首页而失效；其余冷启动维持"先看首页"。
      this.deepLinkEntry = DEEP_LINK_SCENES.has(Number(entry.scene));
      if (!this.homeSeen && !this.deepLinkEntry && entry.path && entry.path !== 'pages/home/index') {
        returnHome();
      }
    }
    // 授权快照以服务端为准：冷启动/回前台时静默刷新（无网络层环境自动跳过）。
    refreshFromServer();
  }
});
