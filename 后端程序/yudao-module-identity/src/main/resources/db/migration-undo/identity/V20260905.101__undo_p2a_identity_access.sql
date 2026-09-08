-- 逆向脚本：回退 V20260905.101（P2A 身份域表；生产执行前必须核对无活跃会话与有效授权）
DROP TABLE IF EXISTS user_session;
DROP TABLE IF EXISTS design_access_grant;
DROP TABLE IF EXISTS access_code_redemption;
DROP TABLE IF EXISTS design_access_code;
DROP TABLE IF EXISTS design_access_code_batch;
DROP TABLE IF EXISTS wechat_identity;
DROP TABLE IF EXISTS account;
