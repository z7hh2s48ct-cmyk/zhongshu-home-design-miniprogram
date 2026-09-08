-- V20260905.002：基线探针表索引（P1B 演示逐步升级：V001 建表 → V002 加索引）
CREATE INDEX idx_zhongshu_baseline_probe_create_time
    ON zhongshu_baseline_probe (create_time);
