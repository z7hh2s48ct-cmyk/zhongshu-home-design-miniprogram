package cn.iocoder.yudao.module.design.asset;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.rights.RightsGrantService;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.DeliveryPort;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.IssuedTicket;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.TicketConsumption;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.ASSET_VALIDATION_FAILED;

/**
 * 资产生命周期：上传票据 → 客户端直传私有存储 → 完成校验（安全扫描门禁）→ 一次性下载
 *
 * 合同（架构 §6.9 / §10.4）：
 * - 上传票据按 asset_type 强制 MIME/大小策略；完成校验做魔数、实际大小与 SHA-256、像素守卫、
 *   EXIF/GPS 剥离（重编码）、恶意样本、内容审核；任何一项不过即 REJECTED，
 *   REJECTED 资产不能下载、发布或进入 Provider；
 * - 下载走一次性短期票据 + 短期签名 URL；对象级权限：所有者，或存在有效 PUBLIC_DISPLAY 授权；
 * - 扫描明细全部落 asset_scan_result 供审计。
 */
@Slf4j
@Service
public class AssetService {

    public record UploadTicket(long assetId, String uploadUrl) {
    }

    private record AssetRow(long id, long ownerUserId, String assetType, String objectKey,
                            String declaredMime, long sizeBytes, String sha256, String uploadStatus,
                            String validationToken) {
    }

    private static final long UPLOAD_TICKET_TTL_SECONDS = 900;
    private static final long DOWNLOAD_URL_TTL_SECONDS = 300;

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate txTemplate;

    private final ObjectStoragePort storage;

    private final AssetContentScanner scanner;

    private final ContentModerationPort contentModerationPort;

    private final DeliveryPort deliveryPort;

    private final RightsGrantService rightsGrantService;

    public AssetService(DataSource dataSource, PlatformTransactionManager transactionManager,
                        ObjectStoragePort storage, AssetContentScanner scanner,
                        ContentModerationPort contentModerationPort, DeliveryPort deliveryPort,
                        RightsGrantService rightsGrantService) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.storage = storage;
        this.scanner = scanner;
        this.contentModerationPort = contentModerationPort;
        this.deliveryPort = deliveryPort;
        this.rightsGrantService = rightsGrantService;
    }

    /** 申请上传凭证：服务端按类型策略强制 MIME/大小，object key 由服务端生成（客户端不可指定） */
    public UploadTicket createUploadTicket(long userId, String assetType, String mimeType,
                                           long sizeBytes, String sha256Hex) {
        AssetTypePolicy policy;
        try {
            policy = AssetTypePolicy.valueOf(assetType);
        } catch (IllegalArgumentException e) {
            throw exception(ASSET_VALIDATION_FAILED);
        }
        if (!policy.mimeAllowed(mimeType)) {
            throw new ServiceException(1_071_000_001,
                    "资产校验未通过：类型 " + assetType + " 不允许 " + mimeType);
        }
        if (sizeBytes <= 0 || sizeBytes > policy.maxBytes()) {
            throw new ServiceException(1_071_000_001,
                    "资产校验未通过：大小超限（上限 " + policy.maxBytes() + " 字节）");
        }
        if (sha256Hex == null || !sha256Hex.matches("[0-9a-fA-F]{64}")) {
            throw exception(ASSET_VALIDATION_FAILED);
        }
        long assetId = IdWorker.getId();
        String ext = switch (mimeType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "application/pdf" -> ".pdf";
            default -> ".bin";
        };
        String objectKey = "uploads/" + policy.keyPrefix() + "/" + userId + "/" + assetId + ext;
        txTemplate.execute(status -> {
            jdbcTemplate.update(
                    "INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, "
                            + "declared_mime, size_bytes) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    assetId, objectKey, userId, assetType,
                    assetType.startsWith("USER_") ? "USER_UPLOAD"
                            : ("AI_OUTPUT".equals(assetType) ? "AI_GENERATED" : "COMPANY"),
                    sha256Hex.toLowerCase(), mimeType, sizeBytes);
            return null;
        });
        return new UploadTicket(assetId, storage.presignUploadUrl(objectKey, UPLOAD_TICKET_TTL_SECONDS));
    }

    /** 管理端上传复用相同资产策略、私有存储和完整扫描链路，不接受客户端对象地址。 */
    public long uploadCompanyImage(long adminId, String mime, byte[] content) {
        var ticket = createUploadTicket(adminId, "CASE_IMAGE", mime, content.length, sha256Hex(content));
        storage.putObject(load(ticket.assetId()).objectKey(), content);
        if (!"ACCEPTED".equals(completeUpload(adminId, ticket.assetId()))) throw exception(ASSET_VALIDATION_FAILED);
        return ticket.assetId();
    }

    /** 上传完成：短事务领取 → 事务外扫描 → 短事务固化终态。 */
    public String completeUpload(long userId, long assetId) {
        // (a) 短事务：对象级权限 + 领取校验任务
        AssetRow asset = txTemplate.execute(status -> {
            AssetRow row = loadForUpdate(assetId);
            if (row.ownerUserId() != userId) {
                throw exception(RESOURCE_FORBIDDEN);
            }
            if ("ACCEPTED".equals(row.uploadStatus())) {
                return null; // 幂等：已完成
            }
            // PENDING 可领取；VALIDATING 超过 10 分钟视为残留（(b) 阶段崩溃），允许重新领取
            if (!"PENDING".equals(row.uploadStatus())
                    && !("VALIDATING".equals(row.uploadStatus()) && isStale(row.id()))) {
                throw exception(ASSET_VALIDATION_FAILED);
            }
            String validationToken = java.util.UUID.randomUUID().toString();
            jdbcTemplate.update(
                    "UPDATE asset SET upload_status = 'VALIDATING', validation_token = ?, update_time = now() WHERE id = ?",
                    validationToken, assetId);
            return new AssetRow(row.id(), row.ownerUserId(), row.assetType(), row.objectKey(),
                    row.declaredMime(), row.sizeBytes(), row.sha256(), "VALIDATING", validationToken);
        });
        if (asset == null) {
            return "ACCEPTED";
        }

        // (b) 事务外：对象存在性、SHA-256 与内容安全扫描（可能耗时，不持锁）
        String rejectReason = null;
        byte[] content = null;
        if (!storage.existsWithSize(asset.objectKey(), asset.sizeBytes())) {
            rejectReason = "SIZE_MISMATCH: 未收到与声明大小一致的对象";
        }
        if (rejectReason == null) {
            try (InputStream in = storage.getObject(asset.objectKey())) {
                // HEAD is only a hint: the upload URL can replace the raw object concurrently.
                int limit = Math.toIntExact(AssetTypePolicy.valueOf(asset.assetType()).maxBytes());
                content = in.readNBytes(limit + 1);
                if (content.length != asset.sizeBytes() || content.length > limit) {
                    rejectReason = "SIZE_MISMATCH: 实际读取大小与申报不一致";
                }
            } catch (IOException e) {
                rejectReason = "READ_FAILED: 对象读取失败";
            }
        }
        AssetContentScanner.ScanReport report = null;
        if (rejectReason == null) {
            String actualSha = sha256Hex(content);
            if (!actualSha.equals(asset.sha256())) {
                rejectReason = "SHA_MISMATCH: 实际内容与声明 SHA-256 不一致";
            }
        }
        if (rejectReason == null) {
            report = scanner.scan(asset.declaredMime(), content);
            if (!report.passed()) {
                rejectReason = String.join("; ", report.failures());
            }
        }
        boolean moderationFailed = rejectReason == null
                && !contentModerationPort.pass(asset.assetType(), report.sanitizedContent());

        // (c) 短事务：按 VALIDATING→终态 CAS 固化；并发完成者在此落败
        final String finalRejectReason = rejectReason;
        final boolean finalModerationFailed = moderationFailed;
        final AssetContentScanner.ScanReport finalReport = report;
        final byte[] sanitized = report == null ? null : report.sanitizedContent();
        // The original PUT URL must never address the approved object. Every attempt writes
        // a unique server-only key outside the DB transaction; CAS chooses the winning version.
        final String acceptedKey = "accepted/" + assetId + "/" + asset.validationToken()
                + switch (asset.declaredMime()) {
                    case "image/jpeg" -> ".jpg";
                    case "image/png" -> ".png";
                    case "application/pdf" -> ".pdf";
                    default -> ".bin";
                };
        if (rejectReason == null && !moderationFailed) storage.putObject(acceptedKey, sanitized);
        return txTemplate.execute(status -> {
            int updated;
            if (finalReport != null) {
                recordScanResults(assetId, finalReport);
            }
            if (finalRejectReason != null) {
                updated = jdbcTemplate.update(
                        "UPDATE asset SET upload_status = 'REJECTED', security_scan_status = 'REJECTED', "
                                + "rejected_reason = ?, update_time = now() "
                                + "WHERE id = ? AND upload_status = 'VALIDATING' AND validation_token = ?",
                        finalRejectReason, assetId, asset.validationToken());
                if (updated == 1) {
                    log.warn("[completeUpload][asset={} REJECTED 原因={}]", assetId, finalRejectReason);
                    return "REJECTED";
                }
                throw exception(ASSET_VALIDATION_FAILED);
            }
            if (finalModerationFailed) {
                recordScan(assetId, "CONTENT_MODERATION", "REJECTED", null);
                updated = jdbcTemplate.update(
                        "UPDATE asset SET moderation_status = 'REJECTED', upload_status = 'REJECTED', "
                                + "rejected_reason = 'CONTENT_MODERATION', update_time = now() "
                                + "WHERE id = ? AND upload_status = 'VALIDATING' AND validation_token = ?",
                        assetId, asset.validationToken());
                if (updated != 1) throw exception(ASSET_VALIDATION_FAILED);
                return "REJECTED";
            }
            if (finalReport.exifDetected()) {
                // EXIF 剥离不是拒绝：检测留痕审计，元数据已随重编码消失
                recordScan(assetId, "EXIF_STRIP", "PASSED", "元数据已随重编码剥离");
            }
            updated = jdbcTemplate.update(
                    "UPDATE asset SET upload_status = 'ACCEPTED', security_scan_status = 'PASSED', "
                            + "moderation_status = 'PASSED', object_key = ?, size_bytes = ?, width = ?, height = ?, "
                            + "page_count = ?, stored_sha256 = ?, stored_size = ?, update_time = now() "
                            + "WHERE id = ? AND upload_status = 'VALIDATING' AND validation_token = ?",
                    acceptedKey, (long) sanitized.length, finalReport.width(), finalReport.height(),
                    finalReport.pageCount(), sha256Hex(sanitized), (long) sanitized.length, assetId, asset.validationToken());
            if (updated == 1) {
                log.info("[completeUpload][asset={} ACCEPTED size={} type={}]",
                        assetId, sanitized.length, asset.assetType());
                return "ACCEPTED";
            }
            throw exception(ASSET_VALIDATION_FAILED);
        });
    }

    /** 申请一次性下载票据：对象级权限 + 扫描门禁 */
    public IssuedTicket requestDownloadTicket(long userId, long assetId) {
        AssetRow asset = load(assetId);
        boolean allowed = asset.ownerUserId() == userId;
        if (!allowed) {
            allowed = rightsGrantService.hasEffectiveGrant(assetId, "PUBLIC_DISPLAY");
        }
        if (!allowed) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        requireUsable(asset);
        return deliveryPort.issueDownloadTicket("ASSET_DOWNLOAD",
                String.valueOf(assetId), userId, DOWNLOAD_URL_TTL_SECONDS * 2L);
    }

    /** 凭一次性票据换短期签名 URL；公开授权被撤回后立即拒绝（读链路实时判定） */
    public String resolveDownloadTicket(long userId, long assetId, String ticketToken) {
        TicketConsumption consumption = deliveryPort.consumeDownloadTicket(ticketToken,
                String.valueOf(userId));
        if (consumption.getOutcome() != TicketConsumption.Outcome.CONSUMED_NOW
                || !"ASSET_DOWNLOAD".equals(consumption.getPurpose())
                || !String.valueOf(assetId).equals(consumption.getBizRef())) {
            throw exception(ASSET_VALIDATION_FAILED);
        }
        AssetRow asset = load(assetId);
        boolean allowed = asset.ownerUserId() == userId
                || rightsGrantService.hasEffectiveGrant(assetId, "PUBLIC_DISPLAY");
        if (!allowed) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        requireUsable(asset);
        return storage.presignDownloadUrl(asset.objectKey(), DOWNLOAD_URL_TTL_SECONDS);
    }

    /** 开发/联调用：凭一次性票据直接读取对象字节（与 resolveDownloadTicket 同一套权限与门禁） */
    public DownloadContent readForTicket(long userId, long assetId, String ticketToken) {
        TicketConsumption consumption = deliveryPort.consumeDownloadTicket(ticketToken,
                String.valueOf(userId));
        if (consumption.getOutcome() != TicketConsumption.Outcome.CONSUMED_NOW
                || !"ASSET_DOWNLOAD".equals(consumption.getPurpose())
                || !String.valueOf(assetId).equals(consumption.getBizRef())) {
            throw exception(ASSET_VALIDATION_FAILED);
        }
        AssetRow asset = load(assetId);
        boolean allowed = asset.ownerUserId() == userId
                || rightsGrantService.hasEffectiveGrant(assetId, "PUBLIC_DISPLAY");
        if (!allowed) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        requireUsable(asset);
        byte[] content;
        try (InputStream in = storage.getObject(asset.objectKey())) {
            content = in.readAllBytes();
        } catch (IOException e) {
            throw exception(ASSET_VALIDATION_FAILED);
        }
        return new DownloadContent(content, asset.declaredMime());
    }

    public record DownloadContent(byte[] content, String mimeType) {}

    /** 调用端必须具有投稿审核权限；票据绑定管理员和投稿内的冻结资产。 */
    public IssuedTicket requestReviewTicket(long reviewerId, long submissionId, long assetId) {
        requireReviewAsset(submissionId, assetId);
        return deliveryPort.issueDownloadTicket("SUBMISSION_PREVIEW", reviewerId + ":" + submissionId + ":" + assetId, reviewerId, 300);
    }

    public DownloadContent readReviewTicket(long reviewerId, long submissionId, long assetId, String ticket) {
        var asset = requireReviewAsset(submissionId, assetId);
        var consumed = deliveryPort.consumeDownloadTicket(ticket, String.valueOf(reviewerId));
        if (consumed.getOutcome() != TicketConsumption.Outcome.CONSUMED_NOW
                || !"SUBMISSION_PREVIEW".equals(consumed.getPurpose())
                || !(reviewerId + ":" + submissionId + ":" + assetId).equals(consumed.getBizRef())) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        try (InputStream in = storage.getObject(asset.objectKey())) {
            return new DownloadContent(in.readAllBytes(), asset.declaredMime());
        } catch (IOException | IllegalStateException e) {
            throw new cn.iocoder.yudao.framework.common.exception.ServiceException(
                    ASSET_VALIDATION_FAILED.getCode(), "图纸文件不可读取，请核对存储后重试");
        }
    }

    /** 管理端案例预览只允许当前案例关联的已校验资产，不授予公开访问权。 */
    public IssuedTicket requestCasePreviewTicket(long adminId, long caseId, long assetId) {
        requireCaseAsset(caseId, assetId);
        return deliveryPort.issueDownloadTicket("CASE_PREVIEW", adminId + ":" + caseId + ":" + assetId, adminId, 300);
    }

    public DownloadContent readCasePreviewTicket(long adminId, long caseId, long assetId, String ticket) {
        var asset = requireCaseAsset(caseId, assetId);
        var consumed = deliveryPort.consumeDownloadTicket(ticket, String.valueOf(adminId));
        if (consumed.getOutcome() != TicketConsumption.Outcome.CONSUMED_NOW
                || !"CASE_PREVIEW".equals(consumed.getPurpose())
                || !(adminId + ":" + caseId + ":" + assetId).equals(consumed.getBizRef())) throw exception(RESOURCE_FORBIDDEN);
        try (InputStream in = storage.getObject(asset.objectKey())) {
            return new DownloadContent(in.readAllBytes(), asset.declaredMime());
        } catch (IOException | IllegalStateException e) {
            throw new ServiceException(ASSET_VALIDATION_FAILED.getCode(), "案例图纸读取失败，请核对存储后重试");
        }
    }

    private AssetRow requireCaseAsset(long caseId, long assetId) {
        Integer linked = jdbcTemplate.queryForObject("SELECT count(*) FROM design_case c "
                + "JOIN design_case_asset a ON a.case_version_id = c.current_version_id "
                + "WHERE c.id = ? AND c.deleted = FALSE AND a.deleted = FALSE AND a.asset_id = ?",
                Integer.class, caseId, assetId);
        if (linked == null || linked == 0) throw exception(RESOURCE_FORBIDDEN);
        var asset = load(assetId);
        requireUsable(asset);
        return asset;
    }

    private AssetRow requireReviewAsset(long submissionId, long assetId) {
        Integer linked = jdbcTemplate.queryForObject("SELECT count(*) FROM case_submission sub "
                + "JOIN design_result_version v ON v.id=sub.result_version_id AND v.project_id=sub.project_id AND v.deleted=FALSE "
                + "JOIN design_selection s ON s.id IN(v.flat_selection_id,v.elevation_selection_id) AND s.project_id=v.project_id AND s.deleted=FALSE "
                + "JOIN design_candidate c ON c.id=s.candidate_id AND c.project_id=v.project_id AND c.deleted=FALSE "
                + "JOIN asset a ON a.id=c.asset_id AND a.owner_user_id=sub.user_id "
                + "WHERE sub.id=? AND sub.deleted=FALSE AND c.asset_id=?", Integer.class, submissionId, assetId);
        if (linked == null || linked == 0) throw exception(RESOURCE_FORBIDDEN);
        var asset = load(assetId);
        requireUsable(asset);
        return asset;
    }

    /**
     * 开发/联调直传：本地适配器的 local:// 上传地址无法被小程序 wx.uploadFile 访问，
     * 与开发期内容端点同开关管理（zhongshu.design.asset.dev-content-endpoint）。
     * 只做属主/状态/大小核对并落对象，SHA-256/魔数/审核等仍由 completeUpload 强制执行；
     * 真实 COS 适配器就绪后客户端改走预签名 PUT，本方法闲置。
     */
    public void devDirectUpload(long userId, long assetId, long sizeBytes, byte[] content) {
        AssetRow asset = load(assetId);
        if (asset.ownerUserId() != userId) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        if (!"PENDING".equals(asset.uploadStatus())) {
            throw new ServiceException(1_071_000_001,
                    "资产校验未通过：当前状态 " + asset.uploadStatus() + " 不允许直传");
        }
        if (content == null || content.length != sizeBytes || sizeBytes != asset.sizeBytes()) {
            throw new ServiceException(1_071_000_001, "上传内容大小与申报不一致");
        }
        // 申报 SHA 即对象身份：不符直接拒绝。否则存在 check-then-put 竞态可覆盖 completeUpload
        // 已净化落盘的对象（审查 P1-1），加此校验后任何覆盖都必须命中申报哈希，竞态无收益
        if (!sha256Hex(content).equals(asset.sha256())) {
            throw new ServiceException(1_071_000_001, "上传内容与申报 SHA-256 不一致");
        }
        storage.putObject(asset.objectKey(), content);
    }

    // ========== 内部 ==========

    private void requireUsable(AssetRow asset) {
        if (!"ACCEPTED".equals(asset.uploadStatus())) {
            throw new ServiceException(1_071_000_001, "资产校验未通过：当前状态 " + asset.uploadStatus());
        }
    }

    private AssetRow loadForUpdate(long assetId) {
        List<AssetRow> rows = jdbcTemplate.query(
                "SELECT id, owner_user_id, asset_type, object_key, declared_mime, size_bytes, sha256, upload_status, validation_token "
                        + "FROM asset WHERE id = ? AND deleted = FALSE FOR UPDATE",
                (rs, i) -> mapRow(rs), assetId);
        if (rows.isEmpty()) {
            throw exception(ASSET_VALIDATION_FAILED);
        }
        return rows.get(0);
    }

    private AssetRow load(long assetId) {
        List<AssetRow> rows = jdbcTemplate.query(
                "SELECT id, owner_user_id, asset_type, object_key, declared_mime, size_bytes, sha256, upload_status, validation_token "
                        + "FROM asset WHERE id = ? AND deleted = FALSE",
                (rs, i) -> mapRow(rs), assetId);
        if (rows.isEmpty()) {
            throw exception(ASSET_VALIDATION_FAILED);
        }
        return rows.get(0);
    }

    private AssetRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AssetRow(rs.getLong("id"), rs.getLong("owner_user_id"), rs.getString("asset_type"),
                rs.getString("object_key"), rs.getString("declared_mime"), rs.getLong("size_bytes"),
                rs.getString("sha256"), rs.getString("upload_status"), rs.getString("validation_token"));
    }

    private boolean isStale(long assetId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM asset WHERE id = ? AND upload_status = 'VALIDATING' "
                        + "AND update_time < now() - interval '10 minutes'",
                Integer.class, assetId);
        return n != null && n > 0;
    }

    private void recordScan(long assetId, String scanType, String status, String detail) {
        jdbcTemplate.update(
                "INSERT INTO asset_scan_result (id, asset_id, scan_type, status, detail) "
                        + "VALUES (?, ?, ?, ?, JSONB_BUILD_OBJECT('detail', ?))",
                IdWorker.getId(), assetId, scanType, status, detail);
    }

    private void recordScanResults(long assetId, AssetContentScanner.ScanReport report) {
        for (String failure : report.failures()) {
            String scanType = failure.split(":")[0];
            jdbcTemplate.update(
                    "INSERT INTO asset_scan_result (id, asset_id, scan_type, status, detail) "
                            + "VALUES (?, ?, ?, 'REJECTED', JSONB_BUILD_OBJECT('detail', ?))",
                    IdWorker.getId(), assetId, scanType, failure);
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
