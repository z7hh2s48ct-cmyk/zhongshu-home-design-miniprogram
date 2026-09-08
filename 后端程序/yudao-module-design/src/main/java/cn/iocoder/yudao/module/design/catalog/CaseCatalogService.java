package cn.iocoder.yudao.module.design.catalog;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 案例目录（架构 §6.2 / 蓝图 P3B）
 *
 * 合同：
 * - 版本不可变：编辑生成新版本行并 CAS 切换 current_version_id，过期版本返回 STATE_VERSION_CONFLICT；
 * - 列表只返回 PUBLISHED，稳定游标分页（building_area ASC, id ASC），同面积不重页/漏页；
 * - 下架即时不可见；公司案例可重新上架（新发布事实），AI 案例的发布在 P7A 审核链中；
 * - 收藏 (user_id, case_id) 唯一，并发幂等；批量操作逐项返回结果不静默半成功。
 */
@Slf4j
@Service
public class CaseCatalogService {

    public record CaseSummary(long caseId, String title, String sourceType, String styleCode,
                              int floorCount, int buildingArea, String coverAssetId,
                              long version, String publicationStatus,
                              java.time.Instant updatedAt) {
    }

    public record CaseDetail(long caseId, String title, String description, String sourceType,
                             String styleCode, int floorCount, int buildingArea,
                             Integer faceWidth, Integer depth,
                             List<String> floorPlanAssetIds, String coverAssetId,
                             String elevationAssetId, String pdfAssetId,
                             long version, String publicationStatus,
                             List<Map<String, Object>> rooms, List<String> tags) {
    }

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate txTemplate;

    public CaseCatalogService(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ========== 后台：创建 / 编辑 / 发布 / 下架 / 批量 ==========

    public long createCompanyCase(long adminUserId, String title, String description, String styleCode,
                                  int floorCount, int buildingArea, Integer faceWidth, Integer depth,
                                  List<Map<String, Object>> rooms, List<String> tags) {
        long caseId = IdWorker.getId();
        long versionId = IdWorker.getId();
        txTemplate.execute(status -> {
            jdbcTemplate.update(
                    "INSERT INTO design_case (id, source_type, creator_user_id, current_version_id, "
                            + "publication_status) VALUES (?, 'COMPANY', ?, ?, 'DRAFT')",
                    caseId, adminUserId, versionId);
            jdbcTemplate.update(
                    "INSERT INTO design_case_version (id, case_id, version, title, description, style_code, "
                            + "floor_count, building_area, face_width, depth, rooms, tags) "
                            + "VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, "
                            + "CAST(? AS jsonb), CAST(? AS jsonb))",
                    versionId, caseId, title, description, styleCode, floorCount, buildingArea,
                    faceWidth, depth, toJson(rooms), toJsonStrings(tags));
            return null;
        });
        return caseId;
    }

    /** 编辑：生成新版本行（旧版本不可变）并 CAS 切换；expectedVersion 不匹配抛 STATE_VERSION_CONFLICT */
    public long updateCase(long caseId, long adminUserId, long expectedVersion, String title,
                           String description, String styleCode, int floorCount, int buildingArea,
                           Integer faceWidth, Integer depth, List<Map<String, Object>> rooms,
                           List<String> tags) {
        return txTemplate.execute(status -> {
            List<Map<String, Object>> caseRows = jdbcTemplate.queryForList(
                    "SELECT current_version_id, publication_status FROM design_case "
                            + "WHERE id = ? AND deleted = FALSE FOR UPDATE", caseId);
            if (caseRows.isEmpty() || caseRows.get(0).get("current_version_id") == null) {
                throw exception(RESOURCE_FORBIDDEN);
            }
            Map<String, Object> c = caseRows.get(0);
            Long currentVersionId = ((Number) c.get("current_version_id")).longValue();
            Long currentVersion = jdbcTemplate.queryForObject(
                    "SELECT version FROM design_case_version WHERE id = ?", Long.class, currentVersionId);
            if (currentVersion == null || currentVersion != expectedVersion) {
                throw exception(STATE_VERSION_CONFLICT);
            }
            long newVersionId = IdWorker.getId();
            jdbcTemplate.update(
                    "INSERT INTO design_case_version (id, case_id, version, title, description, style_code, "
                            + "floor_count, building_area, face_width, depth, rooms, tags) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))",
                    newVersionId, caseId, expectedVersion + 1, title, description, styleCode,
                    floorCount, buildingArea, faceWidth, depth, toJson(rooms), toJsonStrings(tags));
            jdbcTemplate.update(
                    "UPDATE design_case SET current_version_id = ?, update_time = now() "
                            + "WHERE id = ? AND current_version_id = ?",
                    newVersionId, caseId, currentVersionId);
            // 资产关系随版本继承（资产行数少，Java 端逐行生成新 ID）
            var assets = jdbcTemplate.queryForList(
                    "SELECT asset_id, asset_role, floor_no FROM design_case_asset "
                            + "WHERE case_version_id = ? AND deleted = FALSE", currentVersionId);
            for (Map<String, Object> a : assets) {
                jdbcTemplate.update(
                        "INSERT INTO design_case_asset (id, case_version_id, asset_id, asset_role, floor_no) "
                                + "VALUES (?, ?, ?, ?, ?)",
                        IdWorker.getId(), newVersionId,
                        ((Number) a.get("asset_id")).longValue(),
                        (String) a.get("asset_role"),
                        a.get("floor_no") == null ? null : ((Number) a.get("floor_no")).intValue());
            }
            log.info("[updateCase][case={} v{} → v{}]", caseId, expectedVersion, expectedVersion + 1);
            return newVersionId;
        });
    }

    /** 上架：公司案例 DRAFT/OFFLINE → PUBLISHED（新发布事实）；AI 案例走 P7A 审核链 */
    public boolean publish(long caseId, String operator) {
        return txTemplate.execute(status -> {
            List<String> sourceRows = jdbcTemplate.query(
                    "SELECT source_type FROM design_case WHERE id = ? AND deleted = FALSE FOR UPDATE",
                    (rs, i) -> rs.getString("source_type"), caseId);
            if (sourceRows.isEmpty()) {
                return false; // 不存在/已删：逐项失败，不抛系统异常
            }
            String sourceType = sourceRows.get(0);
            if (!"COMPANY".equals(sourceType)) {
                throw exception(RESOURCE_FORBIDDEN); // AI 案例必须经 P7A 审核与独立发布命令
            }
            Long versionId = jdbcTemplate.queryForObject(
                    "SELECT current_version_id FROM design_case WHERE id = ?", Long.class, caseId);
            int updated = jdbcTemplate.update(
                    "UPDATE design_case SET publication_status = 'PUBLISHED', update_time = now() "
                            + "WHERE id = ? AND publication_status IN ('DRAFT','OFFLINE')", caseId);
            if (updated == 1) {
                jdbcTemplate.update(
                        "INSERT INTO case_publication (id, case_id, action, published_version_id, operator_id) "
                                + "VALUES (?, ?, 'PUBLISH', ?, ?)",
                        IdWorker.getId(), caseId, versionId, operator);
            }
            return updated == 1;
        });
    }

    /** 下架：即时不可见；留下架事实与原因 */
    public boolean offline(long caseId, String reason, String operator) {
        return txTemplate.execute(status -> {
            int updated = jdbcTemplate.update(
                    "UPDATE design_case SET publication_status = 'OFFLINE', update_time = now() "
                            + "WHERE id = ? AND publication_status = 'PUBLISHED'", caseId);
            if (updated == 1) {
                jdbcTemplate.update(
                        "INSERT INTO case_publication (id, case_id, action, reason, operator_id) "
                                + "VALUES (?, ?, 'OFFLINE', ?, ?)",
                        IdWorker.getId(), caseId, reason, operator);
            }
            return updated == 1;
        });
    }

    /** 批量操作：逐项执行、逐项返回结果（不静默半成功） */
    public List<Map<String, Object>> bulkPublicationAction(List<Long> caseIds, boolean publish,
                                                           String reason, String operator) {
        List<Map<String, Object>> items = new ArrayList<>(caseIds.size());
        for (Long caseId : caseIds) {
            String errorCode = null;
            try {
                boolean ok = publish ? publish(caseId, operator) : offline(caseId, reason, operator);
                if (!ok) {
                    errorCode = "PUBLICATION_VALIDATION_FAILED";
                }
            } catch (ServiceException e) {
                errorCode = "PUBLICATION_VALIDATION_FAILED";
            }
            items.add(Map.of("targetId", String.valueOf(caseId),
                    "success", errorCode == null,
                    "errorCode", errorCode == null ? "" : errorCode));
        }
        return items;
    }

    // ========== 小程序：列表 / 详情 / 收藏 / 首页 ==========

    public record CursorPage(List<CaseSummary> list, String nextCursor) {
    }

    /** 户型库列表：仅 PUBLISHED，稳定游标（building_area ASC, id ASC） */
    public CursorPage listPublishedCases(String sourceType, String styleCode, Integer floorCount,
                                         Integer minArea, Integer maxArea,
                                         String cursor, int limit) {
        StringBuilder where = new StringBuilder(
                " WHERE c.deleted = FALSE AND c.publication_status = 'PUBLISHED' ");
        List<Object> args = new ArrayList<>();
        if (sourceType != null && !sourceType.isBlank()) {
            where.append(" AND c.source_type = ?");
            args.add(sourceType);
        }
        long[] cursorTuple = decodeCursor(cursor);
        if (cursorTuple != null) {
            where.append(" AND (v.building_area > ? OR (v.building_area = ? AND c.id > ?))");
            args.add(cursorTuple[0]);
            args.add(cursorTuple[0]);
            args.add(cursorTuple[1]);
        }
        if (styleCode != null && !styleCode.isBlank()) {
            where.append(" AND v.style_code = ?");
            args.add(styleCode);
        }
        if (floorCount != null) {
            where.append(" AND v.floor_count = ?");
            args.add(floorCount);
        }
        if (minArea != null) {
            where.append(" AND v.building_area >= ?");
            args.add(minArea);
        }
        if (maxArea != null) {
            where.append(" AND v.building_area <= ?");
            args.add(maxArea);
        }
        args.add(Math.min(limit, 50));
        List<CaseSummary> list = jdbcTemplate.query(
                "SELECT c.id, v.title, c.source_type, v.style_code, v.floor_count, v.building_area, "
                        + "v.version, c.publication_status, "
                        + "(SELECT a.asset_id FROM design_case_asset a WHERE a.case_version_id = v.id "
                        + "  AND a.asset_role = 'COVER' AND a.deleted = FALSE LIMIT 1) AS cover_asset_id "
                        + "FROM design_case c JOIN design_case_version v ON v.id = c.current_version_id "
                        + where + " ORDER BY v.building_area ASC, c.id ASC LIMIT ?",
                (rs, i) -> new CaseSummary(rs.getLong("id"), rs.getString("title"),
                        rs.getString("source_type"), rs.getString("style_code"),
                        rs.getInt("floor_count"), rs.getInt("building_area"),
                        rs.getString("cover_asset_id"), rs.getLong("version"),
                        rs.getString("publication_status"), null),
                args.toArray());
        String nextCursor = list.size() == Math.min(limit, 50) && !list.isEmpty()
                ? encodeCursor(list.get(list.size() - 1).buildingArea(), list.get(list.size() - 1).caseId())
                : null;
        return new CursorPage(list, nextCursor);
    }

    /** 详情：仅 PUBLISHED 对外可见（后台管理另行查询） */
    public Optional<CaseDetail> getPublishedCase(long caseId) {
        List<CaseDetail> rows = jdbcTemplate.query(
                "SELECT c.id, v.id AS version_row_id, v.title, v.description, c.source_type, "
                        + "v.style_code, v.floor_count, v.building_area, v.face_width, v.depth, "
                        + "v.version, c.publication_status, v.rooms, v.tags FROM design_case c "
                        + "JOIN design_case_version v ON v.id = c.current_version_id "
                        + "WHERE c.id = ? AND c.deleted = FALSE AND c.publication_status = 'PUBLISHED'",
                (rs, i) -> {
                    long vid = rs.getLong("version_row_id");
                    return new CaseDetail(caseId, rs.getString("title"), rs.getString("description"),
                            rs.getString("source_type"), rs.getString("style_code"),
                            rs.getInt("floor_count"), rs.getInt("building_area"),
                            (Integer) rs.getObject("face_width"), (Integer) rs.getObject("depth"),
                            queryAssetIds(vid, "FLOOR_PLAN"),
                            querySingleAsset(vid, "COVER"),
                            querySingleAsset(vid, "ELEVATION"),
                            querySingleAsset(vid, "PDF"),
                            rs.getLong("version"), rs.getString("publication_status"),
                            fromJson(rs.getString("rooms")), fromJsonStrings(rs.getString("tags")));
                },
                caseId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 管理端分页：包含草稿/下架，来源与状态过滤参数化 */
    public long countCasesForAdmin(String sourceType, String publicationStatus) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM design_case c WHERE c.deleted = FALSE" + adminFilters(sourceType, publicationStatus),
                Long.class, adminFilterArgs(sourceType, publicationStatus));
        return n == null ? 0 : n;
    }

    public List<CaseSummary> pageCasesForAdmin(String sourceType, String publicationStatus,
                                               int pageNo, int pageSize) {
        return jdbcTemplate.query(
                "SELECT c.id, v.title, c.source_type, v.style_code, v.floor_count, v.building_area, "
                        + "v.version, c.publication_status, c.update_time, NULL AS cover_asset_id "
                        + "FROM design_case c LEFT JOIN design_case_version v ON v.id = c.current_version_id "
                        + "WHERE c.deleted = FALSE" + adminFilters(sourceType, publicationStatus)
                        + " ORDER BY c.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new CaseSummary(rs.getLong("id"), rs.getString("title"),
                        rs.getString("source_type"), rs.getString("style_code"),
                        rs.getInt("floor_count"), rs.getInt("building_area"),
                        rs.getString("cover_asset_id"), rs.getLong("version"),
                        rs.getString("publication_status"),
                        rs.getTimestamp("update_time") == null ? null
                                : rs.getTimestamp("update_time").toInstant()),
                toPagedArgs(adminFilterArgs(sourceType, publicationStatus), pageNo, pageSize));
    }

    private String adminFilters(String sourceType, String publicationStatus) {
        StringBuilder sb = new StringBuilder();
        if (sourceType != null && !sourceType.isBlank()) {
            sb.append(" AND c.source_type = ?");
        }
        if (publicationStatus != null && !publicationStatus.isBlank()) {
            sb.append(" AND c.publication_status = ?");
        }
        return sb.toString();
    }

    private Object[] adminFilterArgs(String sourceType, String publicationStatus) {
        List<Object> args = new ArrayList<>();
        if (sourceType != null && !sourceType.isBlank()) {
            args.add(sourceType);
        }
        if (publicationStatus != null && !publicationStatus.isBlank()) {
            args.add(publicationStatus);
        }
        return args.toArray();
    }

    private Object[] toPagedArgs(Object[] args, int pageNo, int pageSize) {
        Object[] result = new Object[args.length + 2];
        System.arraycopy(args, 0, result, 0, args.length);
        result[args.length] = Math.min(pageSize, 100);
        result[args.length + 1] = (long) Math.max(pageNo - 1, 0) * pageSize;
        return result;
    }

    // ========== 收藏 ==========

    /** 收藏（并发幂等）：返回是否新增 */
    public boolean favorite(long userId, long caseId) {
        int inserted = jdbcTemplate.update(
                "INSERT INTO case_favorite (id, user_id, case_id) VALUES (?, ?, ?) "
                        + "ON CONFLICT (user_id, case_id) DO NOTHING",
                IdWorker.getId(), userId, caseId);
        return inserted == 1;
    }

    public boolean unfavorite(long userId, long caseId) {
        return jdbcTemplate.update(
                "DELETE FROM case_favorite WHERE user_id = ? AND case_id = ?", userId, caseId) == 1;
    }

    /** 收藏列表：仅 PUBLISHED，按收藏时间倒序游标（id DESC） */
    public CursorPage listFavorites(long userId, String cursor, int limit) {
        long[] cursorTuple = decodeCursor(cursor);
        StringBuilder where = new StringBuilder(
                " WHERE f.user_id = ? AND c.publication_status = 'PUBLISHED' AND c.deleted = FALSE ");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (cursorTuple != null) {
            where.append(" AND f.id < ?");
            args.add(cursorTuple[1]);
        }
        args.add(Math.min(limit, 50));
        List<Map<String, Object>> favRows = jdbcTemplate.queryForList(
                "SELECT f.id AS fav_id, c.id AS case_id, v.title, c.source_type, v.style_code, "
                        + "v.floor_count, v.building_area, v.version, c.publication_status "
                        + "FROM case_favorite f "
                        + "JOIN design_case c ON c.id = f.case_id "
                        + "JOIN design_case_version v ON v.id = c.current_version_id "
                        + where + " ORDER BY f.id DESC LIMIT ?",
                args.toArray());
        List<CaseSummary> list = favRows.stream().map(row -> new CaseSummary(
                ((Number) row.get("case_id")).longValue(),
                (String) row.get("title"), (String) row.get("source_type"),
                (String) row.get("style_code"), ((Number) row.get("floor_count")).intValue(),
                ((Number) row.get("building_area")).intValue(), null,
                ((Number) row.get("version")).longValue(),
                (String) row.get("publication_status"), null)).toList();
        String nextCursor = list.size() == Math.min(limit, 50) && !list.isEmpty()
                ? encodeCursor(0, ((Number) favRows.get(favRows.size() - 1).get("fav_id")).longValue())
                : null;
        return new CursorPage(list, nextCursor);
    }

    // ========== 内部 ==========

    private String querySingleAsset(long versionId, String role) {
        List<String> ids = jdbcTemplate.query(
                "SELECT asset_id FROM design_case_asset WHERE case_version_id = ? AND asset_role = ? "
                        + "AND deleted = FALSE LIMIT 1",
                (rs, i) -> String.valueOf(rs.getLong("asset_id")), versionId, role);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private List<String> queryAssetIds(long versionId, String role) {
        return jdbcTemplate.query(
                "SELECT asset_id FROM design_case_asset WHERE case_version_id = ? AND asset_role = ? "
                        + "AND deleted = FALSE ORDER BY floor_no NULLS FIRST, id",
                (rs, i) -> String.valueOf(rs.getLong("asset_id")), versionId, role);
    }

    private static String toJson(List<Map<String, Object>> value) {
        try {
            return value == null ? null
                    : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toJsonStrings(List<String> value) {
        try {
            return value == null ? null
                    : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Map<String, Object>> fromJson(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                    });
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> fromJsonStrings(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 游标：Base64(JSON {a: 面积, i: 案例 ID})；收藏列表只用 i */
    static String encodeCursor(long area, long caseId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"a\":" + area + ",\"i\":" + caseId + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static long[] decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(cursor),
                    java.nio.charset.StandardCharsets.UTF_8);
            long area = Long.parseLong(json.replaceAll(".*\"a\":(-?[0-9]+).*", "$1"));
            long id = Long.parseLong(json.replaceAll(".*\"i\":(-?[0-9]+).*", "$1"));
            return new long[]{area, id};
        } catch (Exception e) {
            throw new ServiceException(400, "游标无效");
        }
    }

}
