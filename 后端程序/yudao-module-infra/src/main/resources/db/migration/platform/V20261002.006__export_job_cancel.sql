-- 导出任务取消支持：运营可取消排队中/生成中的任务（F-5）。
-- worker 认领与续租 SQL 均检查该列；已进入生成尾段的任务仍可能完成（幂等，不阻断）。
ALTER TABLE export_job ADD COLUMN IF NOT EXISTS cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;
