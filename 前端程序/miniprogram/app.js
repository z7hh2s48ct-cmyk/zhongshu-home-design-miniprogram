const { returnHome, refreshFromServer } = require('./utils/access');

App({
  homeSeen: false,
  globalData: {
    projectId: null, jobId: null,
    selectedCandidateId: null, selectedElevationId: null,
    selectedFlatAssetId: null, selectedElevationAssetId: null,
    flatLabel: '', elevationLabel: '', resultVersionId: null,
    elevationConfig: null, refCase: null, budgetEstimate: null
  },
  onShow(options) {
    // 仅冷启动回首页；相机、支付等返回前台时保留当前操作。
    if (!this.homeSeen && options.path && options.path !== 'pages/home/index') returnHome();
    // 授权快照以服务端为准：冷启动/回前台时静默刷新（无网络层环境自动跳过）。
    refreshFromServer();
  }
});
