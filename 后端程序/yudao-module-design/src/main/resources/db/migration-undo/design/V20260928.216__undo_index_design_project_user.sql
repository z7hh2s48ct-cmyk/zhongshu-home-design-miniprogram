-- V20260928.216 undo：移除 design_project 的 user_id 索引
DROP INDEX IF EXISTS idx_design_project_user_id;
