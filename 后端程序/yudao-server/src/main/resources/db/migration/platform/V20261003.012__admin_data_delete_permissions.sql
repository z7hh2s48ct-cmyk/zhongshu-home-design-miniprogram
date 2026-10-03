-- Deletion is separately grantable per business resource. Keep export access as an additional gate.
DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL OR to_regclass('public.system_role_menu') IS NULL THEN
    RETURN;
  END IF;

  INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component,
                           component_name, status, visible, keep_alive, always_show,
                           creator, create_time, updater, update_time, deleted) VALUES
  (9560, '删除客户', 'identity:account:delete', 3, 4, 9523, '', '', NULL, NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9561, '删除积分流水', 'commerce:points:delete', 3, 4, 9509, '', '', NULL, NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9562, '删除充值订单', 'commerce:recharge-order:delete', 3, 4, 9508, '', '', NULL, NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9563, '删除 AI 任务', 'aiorchestration:job:delete', 3, 4, 9525, '', '', NULL, NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9564, '删除投稿', 'design:submission:delete', 3, 4, 9503, '', '', NULL, NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9565, '删除预算', 'design:budget:delete', 3, 4, 9522, '', '', NULL, NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9566, '删除案例', 'design:case:delete', 3, 4, 9502, '', '', NULL, NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9567, '删除审计事件', 'design:audit:delete', 3, 4, 9527, '', '', NULL, NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0);

  INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
  SELECT 959000 + id, 1, id, '', now(), '', now(), 0, 0 FROM system_menu WHERE id BETWEEN 9560 AND 9567;
END $$;
