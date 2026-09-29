-- V20260928.216：design_project 补 user_id 索引
--
-- 背景（2026-09-28 整改）：
--   "我的设计项目"列表查询为
--     SELECT ... FROM design_project WHERE user_id = ? AND deleted = FALSE ORDER BY id DESC LIMIT ?
--   （见 DesignProjectService.listProjects），而 V20260905.203 建表时 design_project 仅有主键约束，
--   无任何非主键索引，该查询在项目量增长后退化为全表扫描 + 排序。
--   同批次的 design_candidate(project_id)、design_selection(project_id, stage) 均已建索引，本表属遗漏。
--
-- 索引设计：
--   列顺序 (user_id, id DESC) 同时覆盖等值过滤与降序取页；deleted = FALSE 为高频过滤条件，
--   故采用部分索引（partial index）以减小索引体积并保持与查询谓词一致。
--   项目 id 由雪花算法生成、单调递增，配合 id 降序即为"最新在前"，无需额外排序键。
CREATE INDEX IF NOT EXISTS idx_design_project_user_id
    ON design_project (user_id, id DESC)
    WHERE deleted = FALSE;

COMMENT ON INDEX idx_design_project_user_id IS '我的设计项目列表：user_id 等值过滤 + id 降序取页（部分索引，仅未删除行）';
