-- 逆向脚本：回退 V20260905.003（P1C 平台表；生产执行前必须核对无在途事件/票据）
DROP TABLE IF EXISTS dispatcher_lease;
DROP TABLE IF EXISTS one_time_download_ticket;
DROP TABLE IF EXISTS one_time_delivery_ticket;
DROP TABLE IF EXISTS export_job;
DROP TABLE IF EXISTS audit_event;
DROP TABLE IF EXISTS outbox_event;
