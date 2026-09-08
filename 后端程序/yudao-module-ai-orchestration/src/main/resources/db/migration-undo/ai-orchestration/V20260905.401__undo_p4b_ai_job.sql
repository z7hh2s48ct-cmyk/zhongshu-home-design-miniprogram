-- 逆向脚本：回退 V20260905.401（P4B AI 任务域表；生产执行前必须核对无在途任务）
DROP TABLE IF EXISTS ai_job_settlement;
DROP TABLE IF EXISTS ai_job_result;
DROP TABLE IF EXISTS ai_result_event_inbox;
DROP TABLE IF EXISTS ai_job_attempt;
DROP TABLE IF EXISTS ai_job;
