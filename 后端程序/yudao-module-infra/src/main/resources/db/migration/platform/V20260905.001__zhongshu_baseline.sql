-- V20260905.001：众墅专项迁移链路基线（P1B）
-- 目的：证明 Flyway → PostgreSQL 迁移链路可用，并固化领域表通用列与约束写法约定。
-- 本表无业务含义，仅供迁移链路与回滚演练使用；业务表由各工作包自己的迁移创建。
-- 通用列约定（后续所有领域表遵循，见 docs/zhongshu-design/P1B-迁移与约束基线.md）：
--   id          BIGINT 应用侧雪花 ID（MyBatis-Plus ASSIGN_ID），主键
--   tenant_id   BIGINT 默认 0（底座租户插件约定，业务语义待 D-09 冻结）
--   creator/updater   VARCHAR(64) 审计操作者
--   create_time/update_time TIMESTAMPTZ NOT NULL DEFAULT now()
--   deleted     BOOLEAN NOT NULL DEFAULT FALSE（底座逻辑删除约定）
--   点数/金额一律 BIGINT 存最小单位（点 / 分），余额列必须 CHECK >= 0
--   状态列 VARCHAR(32) + CHECK IN（不用 PG enum，便于演进）

CREATE TABLE zhongshu_baseline_probe
(
    id         BIGINT      NOT NULL,
    probe_name VARCHAR(64) NOT NULL,
    points     BIGINT      NOT NULL DEFAULT 0,
    tenant_id  BIGINT      NOT NULL DEFAULT 0,
    creator    VARCHAR(64) NULL     DEFAULT '',
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater    VARCHAR(64) NULL     DEFAULT '',
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted    BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_zhongshu_baseline_probe PRIMARY KEY (id),
    CONSTRAINT ck_zhongshu_baseline_probe_points_nonnegative CHECK (points >= 0),
    CONSTRAINT uk_zhongshu_baseline_probe_name UNIQUE (probe_name)
);

COMMENT ON TABLE zhongshu_baseline_probe IS '众墅专项迁移链路基线探针表（P1B，无业务含义）';
