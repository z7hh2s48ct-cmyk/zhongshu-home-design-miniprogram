-- V20260911.303：T13-24 预下单参数持久化（C 泳道）
-- 用途：存储 createPrepay 返回的 payParams（JSAPI 六参数），供：
--   ① createOrder 响应直接下发 payParams（前端 wx.requestPayment 拉起支付）
--   ② GET /recharge-orders/{orderId}/pay-params 独立端点（恢复流程重新领取）
-- 安全：payParams 不含私钥/APIv3 密钥，仅含已签名的前端拉起参数（appId/timeStamp/nonceStr/package/signType/paySign）
-- 生命周期：订单支付成功或关闭后 payParams 失效（微信 prepay_id 有效期 2 小时），但保留供审计追溯

ALTER TABLE recharge_order ADD COLUMN IF NOT EXISTS prepay_params JSONB NULL;

COMMENT ON COLUMN recharge_order.prepay_params IS
    'T13-24：预下单返回的前端拉起参数（JSAPI 六参数 JSON），仅 paymentState IN (CREATED,PENDING) 时下发';
