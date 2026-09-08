-- 逆向脚本：回退 V20260905.002
-- Flyway 社区版不自动执行 undo；回滚由人工/工具在受控流程中执行，执行前必须核对环境。
DROP INDEX IF EXISTS idx_zhongshu_baseline_probe_create_time;
