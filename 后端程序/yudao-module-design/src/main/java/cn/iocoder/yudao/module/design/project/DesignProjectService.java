package cn.iocoder.yudao.module.design.project;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.rights.RightsGrantService;
import cn.iocoder.yudao.module.infra.zhongshu.api.AiJobPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.DESIGN_STAGE_CONFLICT;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.GENERATION_REFERENCE_NOT_AUTHORIZED;

/**
 * 设计项目与平面竖切（架构 §6.3、§8.2；蓝图 P5）
 *
 * 合同：
 * - 建项目记录来源快照；参考案例仅 PUBLISHED 可引用；
 * - 创建平面任务同事务：锁定案例版本、校验当前有效 GENERATION_REFERENCE 授权并冻结
 *   grant_id + rights_version 快照（仅公开展示不可生成）→ 委托 AiJobPort 计价扣点建任务；
 *   幂等键保证重复点击返回同一任务；授权撤回后新任务拒绝、已建任务凭快照审计不追溯；
 * - 候选晋升：任务终态后 ACCEPTED 结果晋升为资产（所有者=用户）+ design_candidate，幂等；
 * - 选择：候选属本人项目、任务终态才可选；每阶段一条有效选择，重复选择返回既有。
 */
@Slf4j
@Service
public class DesignProjectService {

    public record ProjectSnapshot(long projectId, long userId, String sourceType, Long refCaseId,
                                  String stage, String status) {
    }

    public record CandidateRow(long candidateId, long jobId, int slotNo, long assetId) {
    }

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate txTemplate;

    private final AiJobPort aiJobPort;

    private final AssetService assetService;

    private final RightsGrantService rightsGrantService;

    private final cn.iocoder.yudao.module.infra.zhongshu.api.QuarantineObjectPort quarantineObjectPort;

    public DesignProjectService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                AiJobPort aiJobPort, AssetService assetService,
                                RightsGrantService rightsGrantService,
                                cn.iocoder.yudao.module.infra.zhongshu.api.QuarantineObjectPort quarantineObjectPort) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.aiJobPort = aiJobPort;
        this.assetService = assetService;
        this.rightsGrantService = rightsGrantService;
        this.quarantineObjectPort = quarantineObjectPort;
    }

    /** 创建项目：CASE_REFERENCE 仅可引用已发布案例并冻结版本；SELF_UPLOAD 需草图资产 */
    public long createProject(long userId, String sourceType, Long refCaseId, Long sketchAssetId,
                              Map<String, Object> requirementInputs) {
        return txTemplate.execute(status -> {
            Long refVersionId = null;
            if ("CASE_REFERENCE".equals(sourceType)) {
                if (refCaseId == null) {
                    throw exception(GENERATION_REFERENCE_NOT_AUTHORIZED);
                }
                List<Long> versions = jdbcTemplate.query(
                        "SELECT current_version_id FROM design_case WHERE id = ? AND deleted = FALSE "
                                + "AND publication_status = 'PUBLISHED'",
                        (rs, i) -> rs.getLong("current_version_id"), refCaseId);
                if (versions.isEmpty()) {
                    throw exception(RESOURCE_FORBIDDEN);
                }
                refVersionId = versions.get(0);
            } else if (!"SELF_UPLOAD".equals(sourceType)) {
                throw exception(RESOURCE_FORBIDDEN);
            }
            long projectId = IdWorker.getId();
            jdbcTemplate.update(
                    "INSERT INTO design_project (id, user_id, source_type, ref_case_id, ref_version_id, stage) "
                            + "VALUES (?, ?, ?, ?, ?, 'FLAT')",
                    projectId, userId, sourceType, refCaseId, refVersionId);
            jdbcTemplate.update(
                    "INSERT INTO design_requirement_snapshot (id, project_id, inputs, sketch_asset_id) "
                            + "VALUES (?, ?, CAST(? AS jsonb), ?)",
                    IdWorker.getId(), projectId,
                    requirementInputs == null ? null
                            : toStringJson(requirementInputs),
                    sketchAssetId);
            log.info("[createProject][project={} user={} source={} ref={}]",
                    projectId, userId, sourceType, refCaseId);
            return projectId;
        });
    }

    public Optional<ProjectSnapshot> getProject(long projectId, long userId) {
        return getProject(projectId).filter(p -> p.userId() == userId);
    }

    public Optional<ProjectSnapshot> getProject(long projectId) {
        List<ProjectSnapshot> rows = jdbcTemplate.query(
                "SELECT id, user_id, source_type, ref_case_id, stage, status FROM design_project "
                        + "WHERE id = ? AND deleted = FALSE",
                (rs, i) -> new ProjectSnapshot(rs.getLong("id"), rs.getLong("user_id"),
                        rs.getString("source_type"),
                        rs.getObject("ref_case_id") == null ? null : rs.getLong("ref_case_id"),
                        rs.getString("stage"), rs.getString("status")),
                projectId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 创建平面任务：参考案例在同事务校验生成参考授权并冻结快照；委托 AiJobPort 扣点建任务（幂等）。
     * 返回 (jobId, 是否新建)。
     */
    public record FlatJobCreated(long jobId, boolean created) {
    }

    public FlatJobCreated createFlatJob(long userId, long projectId, int count, String idempotencyKey) {
        ProjectSnapshot project = getProject(projectId)
                .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        if (project.userId() != userId) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        if (!"FLAT".equals(project.stage())) {
            throw exception(DESIGN_STAGE_CONFLICT);
        }
        // 授权校验+快照（事务内；SELF_UPLOAD 用自己的草图，无需案例授权）
        if ("CASE_REFERENCE".equals(project.sourceType())) {
            ensureGenerationReference(project);
        }
        long jobId = aiJobPort.createFlatJob(userId, count, idempotencyKey, String.valueOf(projectId));
        jdbcTemplate.update(
                "UPDATE design_project SET update_time = now() WHERE id = ?", projectId);
        return new FlatJobCreated(jobId, true);
    }

    /** 校验案例当前版本的全部平面图有有效生成参考授权，并把首个 grant_id+rights_version 冻结进项目 */
    private void ensureGenerationReference(ProjectSnapshot project) {
        List<Long> planAssets = jdbcTemplate.queryForList(
                "SELECT a.asset_id FROM design_case_asset a "
                        + "JOIN design_project p ON p.ref_version_id = a.case_version_id "
                        + "WHERE p.id = ? AND a.asset_role = 'FLOOR_PLAN' AND a.deleted = FALSE",
                Long.class, project.projectId());
        if (planAssets.isEmpty()) {
            throw exception(GENERATION_REFERENCE_NOT_AUTHORIZED);
        }
        Long grantId = null;
        Long rightsVersion = null;
        var snapshot = new java.util.ArrayList<Map<String, Object>>();
        for (Long assetId : planAssets) {
            List<Map<String, Object>> grants = jdbcTemplate.queryForList(
                    "SELECT id, rights_version FROM asset_rights_grant WHERE asset_id = ? "
                            + "AND scope = 'GENERATION_REFERENCE' AND status = 'ACTIVE' "
                            + "AND effective_at <= now() "
                            + "AND (expires_at IS NULL OR expires_at > now()) AND deleted = FALSE",
                    assetId);
            if (grants.isEmpty()) {
                throw exception(GENERATION_REFERENCE_NOT_AUTHORIZED);
            }
            long gId = ((Number) grants.get(0).get("id")).longValue();
            long gVer = ((Number) grants.get(0).get("rights_version")).longValue();
            if (grantId == null) {
                grantId = gId;
                rightsVersion = gVer;
            }
            // 审查遗留：逐资产冻结完整快照，而非只记首个 grant
            snapshot.add(Map.of("assetId", assetId, "grantId", gId, "rightsVersion", gVer));
        }
        jdbcTemplate.update(
                "UPDATE design_project SET rights_grant_id = ?, rights_version = ?, "
                        + "rights_snapshot = CAST(? AS jsonb), update_time = now() WHERE id = ?",
                grantId, rightsVersion, toSnapshotJson(snapshot), project.projectId());
        log.info("[ensureGenerationReference][project={} 冻结 grant={} v{} 快照 {} 项]",
                project.projectId(), grantId, rightsVersion, snapshot.size());
    }

    /** 任务终态后晋升 ACCEPTED 结果为资产+候选（幂等；返回该项目全部候选） */
    public List<CandidateRow> promoteCandidates(long userId, long projectId, long jobId) {
        ProjectSnapshot project = getProject(projectId)
                .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        if (project.userId() != userId) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        AiJobPort.JobView job = aiJobPort.getJob(jobId)
                .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        if (job.userId() != userId) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        String status = job.status();
        if (!isTerminal(status)) {
            throw new IllegalStateException("任务未终态: " + status);
        }
        promoteAcceptedResults(userId, projectId, jobId);
        return listCandidates(projectId);
    }

    /**
     * 驱动器系统触发晋升（C2）：结算落库后即晋升，不等前端查询。
     * userId/projectId 由驱动器从 ai_job 行本身读出，无会话属主校验；
     * 与拉式路径共用晋升循环，uk_design_candidate_job_slot 保证双路径并发幂等。
     */
    public void promoteCandidatesForSettledJob(long userId, long projectId, long jobId) {
        promoteAcceptedResults(userId, projectId, jobId);
    }

    private void promoteAcceptedResults(long userId, long projectId, long jobId) {
        for (AiJobPort.CandidateView result : aiJobPort.listAcceptedResults(jobId)) {
            try {
                txTemplate.execute(s -> {
                    // 结果对象晋升为用户资产（AI_OUTPUT 类型；object key 沿用隔离区键）
                    long assetId = registerPromotedAsset(userId, result);
                    jdbcTemplate.update(
                            "INSERT INTO design_candidate (id, project_id, job_id, slot_no, asset_id, ai_result_id) "
                                    + "VALUES (?, ?, ?, ?, ?, ?)",
                            IdWorker.getId(), projectId, jobId, result.slotNo(), assetId, result.resultId());
                    return null;
                });
            } catch (DuplicateKeyException e) {
                // 已晋升：幂等
            }
        }
    }

    private long registerPromotedAsset(long userId, AiJobPort.CandidateView result) {
        // object key → asset（P3A 资产域），upload 完成态直接 ACCEPTED（Core 校验器已把关）
        long assetId = IdWorker.getId();
        jdbcTemplate.update(
                "INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, "
                        + "declared_mime, size_bytes, upload_status, security_scan_status, moderation_status, "
                        + "stored_sha256, stored_size) "
                        + "VALUES (?, ?, ?, 'AI_OUTPUT', 'AI_GENERATED', ?, ?, ?, 'ACCEPTED', 'PASSED', 'PASSED', ?, ?) "
                        + "ON CONFLICT (object_key) DO NOTHING",
                assetId, result.objectKey(), userId, result.sha256(), result.mimeType(),
                guessSize(result), result.sha256(), guessSize(result));
        Long existing = jdbcTemplate.queryForObject(
                "SELECT id FROM asset WHERE object_key = ?", Long.class, result.objectKey());
        return existing == null ? assetId : existing;
    }

    /** 隔离区对象大小：优先查 storage，失败时以 0 兜底（展示用途） */
    private long guessSize(AiJobPort.CandidateView result) {
        try {
            var bytes = quarantineBytes(result.objectKey());
            return bytes == null ? 0L : bytes.length;
        } catch (Exception e) {
            return 0L;
        }
    }

    private byte[] quarantineBytes(String objectKey) {
        try {
            return quarantineObjectPort.getObject(objectKey);
        } catch (Exception e) {
            return null;
        }
    }

    public List<CandidateRow> listCandidates(long projectId) {
        return jdbcTemplate.query(
                "SELECT id, job_id, slot_no, asset_id FROM design_candidate "
                        + "WHERE project_id = ? AND deleted = FALSE ORDER BY job_id, slot_no",
                (rs, i) -> new CandidateRow(rs.getLong("id"), rs.getLong("job_id"),
                        rs.getInt("slot_no"), rs.getLong("asset_id")),
                projectId);
    }

    /** 选定平面候选：候选属本人项目 + 任务终态 + 幂等（重复选择返回既有选择） */
    public long selectFlatCandidate(long userId, long projectId, long candidateId) {
        ProjectSnapshot project = getProject(projectId)
                .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        if (project.userId() != userId) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        return txTemplate.execute(status -> {
            // 与立面选择对称（审查遗留项）：同候选幂等返回；不同候选=轮换（旧选择失效，可继续生成立面）
            List<Long> actives = jdbcTemplate.query(
                    "SELECT id, candidate_id FROM design_selection WHERE project_id = ? AND stage = 'FLAT' "
                            + "AND active = TRUE AND deleted = FALSE",
                    (rs, i) -> rs.getLong("id"), projectId);
            if (!actives.isEmpty()) {
                Long activeCandidate = jdbcTemplate.queryForObject(
                        "SELECT candidate_id FROM design_selection WHERE id = ?", Long.class, actives.get(0));
                if (activeCandidate != null && activeCandidate == candidateId) {
                    return actives.get(0); // 同候选：幂等
                }
                jdbcTemplate.update(
                        "UPDATE design_selection SET active = FALSE, update_time = now() WHERE id = ?",
                        actives.get(0));
            }
            Map<String, Object> candidate;
            try {
                candidate = jdbcTemplate.queryForMap(
                        "SELECT job_id FROM design_candidate WHERE id = ? AND project_id = ? "
                                + "AND deleted = FALSE",
                        candidateId, projectId);
            } catch (org.springframework.dao.EmptyResultDataAccessException e) {
                throw exception(RESOURCE_FORBIDDEN);
            }
            long jobId = ((Number) candidate.get("job_id")).longValue();
            AiJobPort.JobView job = aiJobPort.getJob(jobId).orElseThrow();
            if (!isTerminal(job.status())) {
                throw new IllegalStateException("任务未终态，不可选择");
            }
            long selectionId = IdWorker.getId();
            jdbcTemplate.update(
                    "INSERT INTO design_selection (id, project_id, stage, candidate_id, selected_by) "
                            + "VALUES (?, ?, 'FLAT', ?, ?)",
                    selectionId, projectId, candidateId, userId);
            jdbcTemplate.update(
                    "UPDATE design_project SET update_time = now() WHERE id = ?", projectId);
            log.info("[selectFlatCandidate][project={} candidate={}]", projectId, candidateId);
            return selectionId;
        });
    }

    // ========== P6：立面、最终版本、调整 ==========

    /** 创建立面任务：必须已选定平面候选；立面配置写入需求快照 v2 */
    public FlatJobCreated createElevationJob(long userId, long projectId, int count, String idempotencyKey,
                                             Map<String, Object> elevationConfig) {
        ProjectSnapshot project = getProject(projectId)
                .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        if (project.userId() != userId) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        // 守卫为「存在 FLAT 有效选择」而非阶段值：调整链在 ELEVATION 阶段仍可再生成
        Long flatSelection = jdbcSelectActive(projectId, "FLAT");
        if (flatSelection == null) {
            throw exception(DESIGN_STAGE_CONFLICT); // 未选定平面不得生成立面
        }
        long jobId = aiJobPort.createElevationJob(userId, count, idempotencyKey, String.valueOf(projectId));
        jdbcTemplate.update(
                "INSERT INTO design_requirement_snapshot (id, project_id, input_version, inputs) "
                        + "SELECT ?, ?, COALESCE(MAX(input_version), 0) + 1, CAST(? AS jsonb) "
                        + "FROM design_requirement_snapshot WHERE project_id = ? AND deleted = FALSE",
                IdWorker.getId(), projectId,
                toStringJson(elevationConfig == null ? Map.of() : elevationConfig), projectId);
        jdbcTemplate.update(
                "UPDATE design_project SET stage = 'ELEVATION', update_time = now() WHERE id = ?", projectId);
        return new FlatJobCreated(jobId, true);
    }

    private Long jdbcSelectActive(long projectId, String stage) {
        List<Long> rows = jdbcTemplate.query(
                "SELECT id FROM design_selection WHERE project_id = ? AND stage = ? "
                        + "AND active = TRUE AND deleted = FALSE",
                (rs, i) -> rs.getLong("id"), projectId, stage);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 选定立面候选：生成不可变最终结果版本（首次 version=1，调整链递增） */
    public record ResultVersionCreated(long versionId, long version) {
    }

    public ResultVersionCreated selectElevationCandidate(long userId, long projectId, long candidateId) {
        ProjectSnapshot project = getProject(projectId)
                .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        if (project.userId() != userId) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        return txTemplate.execute(status -> {
            // 幂等：重复选择同一候选返回既有版本；不同候选=调整轮换（旧 selection 失效，生成新版本）
            Long activeSelection = jdbcSelectActive(projectId, "ELEVATION");
            if (activeSelection != null) {
                Long activeCandidate = jdbcTemplate.queryForObject(
                        "SELECT candidate_id FROM design_selection WHERE id = ?", Long.class, activeSelection);
                if (activeCandidate != null && activeCandidate == candidateId) {
                    Long versionId = jdbcTemplate.queryForObject(
                            "SELECT id FROM design_result_version WHERE project_id = ? AND superseded = FALSE "
                                    + "ORDER BY version DESC LIMIT 1", Long.class, projectId);
                    long v = versionId == null ? -1L : versionId;
                    long ver = v < 0 ? -1 : jdbcTemplate.queryForObject(
                            "SELECT version FROM design_result_version WHERE id = ?", Long.class, v);
                    return new ResultVersionCreated(v, ver);
                }
                jdbcTemplate.update(
                        "UPDATE design_selection SET active = FALSE, update_time = now() WHERE id = ?",
                        activeSelection);
            }
            Map<String, Object> candidate;
            try {
                candidate = jdbcTemplate.queryForMap(
                        "SELECT job_id FROM design_candidate WHERE id = ? AND project_id = ? "
                                + "AND deleted = FALSE", candidateId, projectId);
            } catch (org.springframework.dao.EmptyResultDataAccessException e) {
                throw exception(RESOURCE_FORBIDDEN);
            }
            long jobId = ((Number) candidate.get("job_id")).longValue();
            AiJobPort.JobView job = aiJobPort.getJob(jobId).orElseThrow();
            if (!isTerminal(job.status())) {
                throw new IllegalStateException("任务未终态，不可选择");
            }
            // 平面选择与其候选集（同项目、FLAT 阶段有效选择）
            Long flatSelection = jdbcSelectActive(projectId, "FLAT");
            if (flatSelection == null) {
                throw exception(DESIGN_STAGE_CONFLICT);
            }
            List<Long> flatCandidates = jdbcTemplate.queryForList(
                    "SELECT id FROM design_candidate WHERE project_id = ? AND deleted = FALSE",
                    Long.class, projectId);
            long selectionId = IdWorker.getId();
            jdbcTemplate.update(
                    "INSERT INTO design_selection (id, project_id, stage, candidate_id, selected_by) "
                            + "VALUES (?, ?, 'ELEVATION', ?, ?)",
                    selectionId, projectId, candidateId, userId);
            jdbcTemplate.update(
                    "UPDATE design_project SET update_time = now() WHERE id = ?", projectId);
            Integer maxVersion = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(version), 0) FROM design_result_version WHERE project_id = ?",
                    Integer.class, projectId);
            long version = (maxVersion == null ? 0 : maxVersion) + 1;
            long versionId = IdWorker.getId();
            jdbcTemplate.update(
                    "INSERT INTO design_result_version (id, project_id, version, flat_selection_id, "
                            + "elevation_selection_id, flat_candidate_ids, elevation_candidate_id, config_snapshot) "
                            + "VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb))",
                    versionId, projectId, version, flatSelection, selectionId,
                    toJsonList(flatCandidates), candidateId, latestConfig(projectId));
            // 旧版本标记 superseded（不可变：内容不改，只改活跃标记）
            if (version > 1) {
                jdbcTemplate.update(
                        "UPDATE design_result_version SET superseded = TRUE, update_time = now() "
                                + "WHERE project_id = ? AND version < ?", projectId, version);
                // 完成挂起的调整请求
                jdbcTemplate.update(
                        "UPDATE design_revision_request SET status = 'COMPLETED', new_version_id = ?, "
                                + "update_time = now() WHERE project_id = ? AND status = 'PENDING'",
                        versionId, projectId);
            }
            log.info("[selectElevationCandidate][project={} v{}]", projectId, version);
            return new ResultVersionCreated(versionId, version);
        });
    }

    /** 版本列表（旧版本只读可查，最新在前） */
    public List<Map<String, Object>> listResultVersions(long userId, long projectId) {
        requireOwner(userId, projectId);
        return jdbcTemplate.queryForList(
                "SELECT id, version, flat_selection_id, elevation_selection_id, "
                        + "flat_candidate_ids::text, elevation_candidate_id, config_snapshot::text, "
                        + "superseded, create_time FROM design_result_version "
                        + "WHERE project_id = ? AND deleted = FALSE ORDER BY version DESC",
                projectId);
    }

    /** 调整请求：基于当前最新版本发起新立面任务（调整链）；新版本生成后自动 COMPLETED */
    public record RevisionCreated(long requestId, long newJobId) {
    }

    public RevisionCreated createRevisionRequest(long userId, long projectId, String reason,
                                                 Map<String, Object> configUpdates, int count,
                                                 String idempotencyKey) {
        ProjectSnapshot project = getProject(projectId)
                .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        if (project.userId() != userId) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        List<Long> versionRows = jdbcTemplate.query(
                "SELECT id FROM design_result_version WHERE project_id = ? AND deleted = FALSE "
                        + "ORDER BY version DESC LIMIT 1",
                (rs, i) -> rs.getLong("id"), projectId);
        if (versionRows.isEmpty()) {
            throw exception(DESIGN_STAGE_CONFLICT); // 尚无最终版本，无从调整（先拒后建，不扣点）
        }
        Long latestVersionId = versionRows.get(0);
        // 调整 = 基于已选平面的新一轮立面任务（旧版本保留只读）
        var job = createElevationJob(userId, projectId, count, idempotencyKey, configUpdates);
        long requestId = IdWorker.getId();
        jdbcTemplate.update(
                "INSERT INTO design_revision_request (id, project_id, from_version_id, reason, "
                        + "config_updates, new_job_id) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)",
                requestId, projectId, latestVersionId, reason,
                configUpdates == null ? null : toStringJson(configUpdates), job.jobId());
        return new RevisionCreated(requestId, job.jobId());
    }

    // ========== 项目列表与聚合读（"我的 → 设计记录"、项目详情） ==========

    public record ProjectListItem(long projectId, String sourceType, Long refCaseId, String stage,
                                  String status, java.time.Instant createTime) {
    }

    public record ProjectPage(List<ProjectListItem> list, String nextCursor) {
    }

    /**
     * 我的设计项目（游标分页，最新在前）。
     * 游标即上一页最后一行的 id：项目 id 由雪花算法生成、单调递增且唯一，
     * 直接用 id 降序翻页不会出现重复或漏行，无需复合游标。
     */
    public ProjectPage listProjects(long userId, String cursor, int limit) {
        int size = Math.min(Math.max(limit, 1), 50);
        Long afterId = parseCursor(cursor);
        StringBuilder sql = new StringBuilder(
                "SELECT id, source_type, ref_case_id, stage, status, create_time FROM design_project "
                        + "WHERE user_id = ? AND deleted = FALSE");
        List<Object> args = new java.util.ArrayList<>();
        args.add(userId);
        if (afterId != null) {
            sql.append(" AND id < ?");
            args.add(afterId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(size + 1); // 多取一行判断是否还有下一页
        List<ProjectListItem> rows = jdbcTemplate.query(sql.toString(),
                (rs, i) -> new ProjectListItem(rs.getLong("id"), rs.getString("source_type"),
                        rs.getObject("ref_case_id") == null ? null : rs.getLong("ref_case_id"),
                        rs.getString("stage"), rs.getString("status"),
                        rs.getTimestamp("create_time") == null ? null
                                : rs.getTimestamp("create_time").toInstant()),
                args.toArray());
        boolean hasMore = rows.size() > size;
        List<ProjectListItem> page = hasMore ? rows.subList(0, size) : rows;
        String next = hasMore ? String.valueOf(page.get(page.size() - 1).projectId()) : null;
        return new ProjectPage(List.copyOf(page), next);
    }

    private Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException e) {
            // 游标非法只影响调用方自己的翻页，退化为首页而不是抛错打断列表
            log.warn("[listProjects][非法游标 cursor={}，退化为首页]", cursor);
            return null;
        }
    }

    /** 某阶段当前有效选择所指向的候选 id（无有效选择返回 null） */
    public Long activeSelectionCandidateId(long projectId, String stage) {
        List<Long> rows = jdbcTemplate.query(
                "SELECT candidate_id FROM design_selection WHERE project_id = ? AND stage = ? "
                        + "AND active = TRUE AND deleted = FALSE",
                (rs, i) -> rs.getLong("candidate_id"), projectId, stage);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 当前最新（未被取代）的结果版本 id；尚无版本返回 null */
    public Long latestResultVersionId(long projectId) {
        List<Long> rows = jdbcTemplate.query(
                "SELECT id FROM design_result_version WHERE project_id = ? AND deleted = FALSE "
                        + "ORDER BY version DESC LIMIT 1",
                (rs, i) -> rs.getLong("id"), projectId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void requireOwner(long userId, long projectId) {
        getProject(projectId).filter(p -> p.userId() == userId)
                .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
    }

    private String latestConfig(long projectId) {
        List<String> configs = jdbcTemplate.query(
                "SELECT inputs::text FROM design_requirement_snapshot WHERE project_id = ? "
                        + "AND deleted = FALSE ORDER BY input_version DESC LIMIT 1",
                (rs, i) -> rs.getString(1), projectId);
        return configs.isEmpty() ? null : configs.get(0);
    }

    private String toSnapshotJson(java.util.List<Map<String, Object>> items) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(items);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJsonList(List<Long> ids) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(ids);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "PARTIALLY_SUCCEEDED".equals(status);
    }

    private String toStringJson(Map<String, Object> value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
