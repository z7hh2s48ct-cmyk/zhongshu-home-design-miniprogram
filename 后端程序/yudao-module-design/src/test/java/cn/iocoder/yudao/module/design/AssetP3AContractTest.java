package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.asset.AssetContentScanner;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.asset.LocalObjectStorageAdapter;
import cn.iocoder.yudao.module.design.asset.StubContentModerationAdapter;
import cn.iocoder.yudao.module.design.rights.RightsGrantService;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.JdbcDeliveryPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P3A 合同测试（真实 PostgreSQL + 本地对象存储适配器）：
 * 伪 MIME、超限、像素守卫（解压炸弹）、恶意样本、SHA 校验、EXIF 剥离、
 * 对象级权限、公开展示/生成参考授权分离、撤回即时拒绝、票据绑定。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssetP3AContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private static final long USER_A = 101L;
    private static final long USER_B = 202L;

    private JdbcTemplate jdbc;
    private LocalObjectStorageAdapter storage;
    private AssetService assetService;
    private RightsGrantService rightsGrantService;
    private cn.iocoder.yudao.module.design.catalog.CompanyCaseImageService companyImages;
    private cn.iocoder.yudao.module.design.catalog.CaseCatalogService cases;

    @BeforeAll
    void setUp() throws Exception {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        DataSource dataSource = ds;
        jdbc = new JdbcTemplate(dataSource);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/platform", "classpath:db/migration/design")
                .load()
                .migrate();

        java.nio.file.Path root = Files.createTempDirectory("zhongshu-assets-test");
        storage = new LocalObjectStorageAdapter(root.toString());
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        rightsGrantService = new RightsGrantService(dataSource);
        assetService = new AssetService(dataSource, txManager, storage,
                new AssetContentScanner(), new StubContentModerationAdapter(),
                new JdbcDeliveryPort(dataSource), rightsGrantService);
        cases = new cn.iocoder.yudao.module.design.catalog.CaseCatalogService(dataSource, txManager);
        companyImages = new cn.iocoder.yudao.module.design.catalog.CompanyCaseImageService(cases, assetService, rightsGrantService, txManager);
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE asset, asset_scan_result, asset_rights_grant, one_time_download_ticket, design_case, design_case_version, design_case_asset, case_publication");
    }

    @Test
    void moderationOutageRemainsPendingAndRecoveryRecordsDecisionBeforeAcceptance() throws Exception {
        var original=assetService;
        java.util.concurrent.atomic.AtomicBoolean unavailable=new java.util.concurrent.atomic.AtomicBoolean(true);
        var port=new cn.iocoder.yudao.module.design.asset.ContentModerationPort() {
            public boolean pass(String type,byte[] content) { return review(type,content).accepted(); }
            public Decision review(String type,byte[] content) {
                if(unavailable.get()) throw new cn.iocoder.yudao.module.design.asset.ModerationUnavailableException();
                return new Decision(true,"tencent-ims","test-request","Pass","test-policy");
            }
        };
        org.springframework.test.util.ReflectionTestUtils.setField(assetService,"contentModerationPort",port);
        try {
            byte[] content=pngBytes(20,20);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            var ticket=assetService.createUploadTicket(USER_A,"USER_SKETCH","image/png",content.length,hash);
            String key=jdbc.queryForObject("SELECT object_key FROM asset WHERE id=?",String.class,ticket.assetId());storage.putObject(key,content);
            assertThat(assetService.completeUpload(USER_A,ticket.assetId())).isEqualTo("PENDING");
            assertThatThrownBy(()->assetService.requestDownloadTicket(USER_A,ticket.assetId())).isInstanceOf(ServiceException.class);
            unavailable.set(false);jdbc.update("UPDATE asset SET moderation_retry_at=now()-interval '1 second' WHERE id=?",ticket.assetId());
            new cn.iocoder.yudao.module.design.asset.AssetValidationRecoveryJob(jdbc.getDataSource(),assetService) {{
                org.springframework.test.util.ReflectionTestUtils.setField(this,"enabled",true);
            }}.tick();
            assertThat(jdbc.queryForObject("SELECT upload_status FROM asset WHERE id=?",String.class,ticket.assetId())).isEqualTo("ACCEPTED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_scan_result WHERE asset_id=? AND status='PASSED' AND detail::text LIKE '%test-request%'",Long.class,ticket.assetId())).isEqualTo(1);
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(original,"contentModerationPort",new StubContentModerationAdapter()); }
    }

    @Test
    void companyImageUploadScansAssociatesVersionsAndSeparatesRights() throws Exception {
        long id = cases.createCompanyCase(501, "上传测试", null, "MODERN", 2, 120, null, null, null, null);
        var cover = companyImages.upload(id, 501, 1, "COVER", null, "image/png", pngBytes(32,32), true, false);
        assertThat(cases.getAdminCase(id).orElseThrow().coverAssetId()).isEqualTo(cover.assetId());
        assertThat(cover.version()).isEqualTo(2);
        assertThat(rightsGrantService.hasEffectiveGrant(Long.parseLong(cover.assetId()), "PUBLIC_DISPLAY")).isTrue();
        assertThat(rightsGrantService.hasEffectiveGrant(Long.parseLong(cover.assetId()), "GENERATION_REFERENCE")).isFalse();
        var plan = companyImages.upload(id, 501, 2, "FLOOR_PLAN", 2, "image/png", pngBytes(40,40), true, true);
        assertThat(cases.getFloorPlans(id).get(0).floorNo()).isEqualTo(2);
        assertThat(rightsGrantService.hasEffectiveGrant(Long.parseLong(plan.assetId()), "GENERATION_REFERENCE")).isTrue();
        var replaced = companyImages.upload(id, 501, 3, "COVER", null, "image/png", pngBytes(48,48), true, false);
        assertThat(cases.getAdminCase(id).orElseThrow().coverAssetId()).isEqualTo(replaced.assetId());
        assertThat(jdbc.queryForObject("SELECT asset_id FROM design_case_asset a JOIN design_case_version v ON v.id=a.case_version_id "
                + "WHERE v.case_id=? AND v.version=2 AND a.asset_role='COVER'", Long.class, id)).isEqualTo(Long.parseLong(cover.assetId()));
        var ticket = assetService.requestCasePreviewTicket(502, id, Long.parseLong(replaced.assetId()));
        assertThat(assetService.readCasePreviewTicket(502, id, Long.parseLong(replaced.assetId()), ticket.getToken()).content()).isNotEmpty();
        assertThat(cases.publish(id, "501")).isTrue();
        var publishedReplacement = companyImages.upload(id, 501, 4, "COVER", null, "image/png", pngBytes(32,32), true, false);
        assertThat(publishedReplacement.version()).isEqualTo(5);
        assertThat(cases.getAdminCase(id).orElseThrow().publicationStatus()).isEqualTo("PUBLISHED");
        assertThat(rightsGrantService.hasEffectiveGrant(Long.parseLong(publishedReplacement.assetId()), "PUBLIC_DISPLAY")).isTrue();
    }

    @Test
    void companyUploadRejectsUnconfirmedUnsafeStaleForeignAndAiAssets() throws Exception {
        long id = cases.createCompanyCase(501, "拒绝测试", null, "MODERN", 1, 100, null, null, null, null);
        assertThatThrownBy(() -> companyImages.upload(id, 501, 1, "COVER", null, "image/png", pngBytes(32,32), false, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> companyImages.upload(id, 501, 1, "FLOOR_PLAN", 2, "image/png", pngBytes(32,32), true, false)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> companyImages.upload(id, 501, 1, "COVER", null, "image/png", "not an image".getBytes(), true, false)).isInstanceOf(ServiceException.class);
        assertThat(cases.getAdminCase(id).orElseThrow().version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_rights_grant", Integer.class)).isZero();
        long foreign = assetService.uploadCompanyImage(502, "image/png", pngBytes(32,32));
        assertThatThrownBy(() -> cases.replaceCompanyImage(id, 501, 1, foreign, "COVER", null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> companyImages.upload(id, 501, 8, "COVER", null, "image/png", pngBytes(32,32), true, false)).isInstanceOf(ServiceException.class);
        jdbc.update("UPDATE design_case SET source_type='AI' WHERE id=?", id);
        assertThatThrownBy(() -> companyImages.upload(id, 501, 1, "COVER", null, "image/png", pngBytes(32,32), true, false)).isInstanceOf(ServiceException.class);
    }

    @Test
    void casePreviewBindsAdminCaseAssetAndSingleUseWithoutPublicGrant() throws Exception {
        long id = uploadAndComplete(USER_A, "USER_SKETCH", "image/png", pngBytes(32, 32));
        jdbc.update("INSERT INTO design_case(id,source_type,creator_user_id,current_version_id) VALUES(901,'COMPANY',101,902)");
        jdbc.update("INSERT INTO design_case_asset(id,case_version_id,asset_id,asset_role) VALUES(903,902,?,'COVER')", id);
        var ticket = assetService.requestCasePreviewTicket(501, 901, id);
        assertThatThrownBy(() -> assetService.readCasePreviewTicket(502, 901, id, ticket.getToken())).isInstanceOf(ServiceException.class);
        var own = assetService.requestCasePreviewTicket(501, 901, id);
        assertThat(assetService.readCasePreviewTicket(501, 901, id, own.getToken()).content()).isNotEmpty();
        assertThatThrownBy(() -> assetService.readCasePreviewTicket(501, 901, id, own.getToken())).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> assetService.requestCasePreviewTicket(501, 904, id)).isInstanceOf(ServiceException.class);
        assertThat(rightsGrantService.hasEffectiveGrant(id, "PUBLIC_DISPLAY")).isFalse();
        jdbc.update("DELETE FROM design_case_asset WHERE id=903");
        assertThatThrownBy(() -> assetService.requestCasePreviewTicket(501, 901, id)).isInstanceOf(ServiceException.class);
    }

    // ========== 辅助 ==========

    @Test
    void avatarMustBeOwnedAcceptedAndDedicatedType() throws Exception {
        var avatars = new cn.iocoder.yudao.module.design.asset.ProfileAvatarAdapter(jdbc.getDataSource());
        long id = uploadAndComplete(USER_A, "USER_AVATAR", "image/png", pngBytes(32, 32));
        avatars.requireOwnedAcceptedAvatar(USER_A, id);
        assertThatThrownBy(() -> avatars.requireOwnedAcceptedAvatar(USER_B, id))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        jdbc.update("UPDATE asset SET moderation_status = 'PENDING' WHERE id = ?", id);
        assertThatThrownBy(() -> avatars.requireOwnedAcceptedAvatar(USER_A, id))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        jdbc.update("UPDATE asset SET moderation_status = 'PASSED', asset_type = 'USER_REFERENCE' WHERE id = ?", id);
        assertThatThrownBy(() -> avatars.requireOwnedAcceptedAvatar(USER_A, id))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void avatarTypeRejectsOversizeAndNonImageContent() {
        assertThatThrownBy(() -> assetService.createUploadTicket(USER_A, "USER_AVATAR", "image/png", 2097153, "a".repeat(64)))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> assetService.createUploadTicket(USER_A, "USER_AVATAR", "application/pdf", 100, "a".repeat(64)))
                .isInstanceOf(ServiceException.class);
    }

    private byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xFF3366);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private byte[] jpegBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x3366FF);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private byte[] jpegWithExif(byte[] baseJpeg) {
        // 在 SOI 后插入 APP1/Exif 段（含假 TIFF 头与 GPS IFD 标记）
        byte[] exif = new byte[]{
                (byte) 0xFF, (byte) 0xE1, 0x00, 0x20, 'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0, 0, 0, 8, 0, 1, 0, 1, 0, 3, 0, 0, 0, 1, 0, 26, 0, 0, 0, 0, 0};
        byte[] result = new byte[baseJpeg.length + exif.length];
        System.arraycopy(baseJpeg, 0, result, 0, 2);
        System.arraycopy(exif, 0, result, 2, exif.length);
        System.arraycopy(baseJpeg, 2, result, 2 + exif.length, baseJpeg.length - 2);
        return result;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long uploadAndComplete(long userId, String assetType, String mime, byte[] content) {
        var ticket = assetService.createUploadTicket(userId, assetType, mime,
                content.length, sha256(content));
        String objectKey = ticket.uploadUrl().substring("local://".length());
        storage.putObject(objectKey, content);
        long assetId = ticket.assetId();
        assetService.completeUpload(userId, assetId);
        return assetId;
    }

    private String assetStatus(long assetId) {
        return jdbc.queryForObject("SELECT upload_status FROM asset WHERE id = ?", String.class, assetId);
    }

    // ========== 1. 快乐路径：JPEG 含 EXIF → 剥离后 ACCEPTED ==========

    @Test
    void jpegExifStrippedAndAccepted() throws Exception {
        byte[] withExif = jpegWithExif(jpegBytes(64, 48));
        assertThat(new String(withExif, StandardCharsets.US_ASCII).contains("Exif")).isTrue();

        long assetId = uploadAndComplete(USER_A, "USER_SKETCH", "image/jpeg", withExif);
        assertThat(assetStatus(assetId)).isEqualTo("ACCEPTED");

        // 库内对象已重编码：不再含 Exif 标记；宽高已记录
        String objectKey = jdbc.queryForObject(
                "SELECT object_key FROM asset WHERE id = ?", String.class, assetId);
        String sanitized = new String(storage.getObject(objectKey).readAllBytes(), StandardCharsets.US_ASCII);
        assertThat(sanitized.contains("Exif")).as("消毒后对象不得含 Exif").isFalse();
        var dims = jdbc.queryForObject(
                "SELECT width, height FROM asset WHERE id = ?",
                (rs, i) -> new int[]{rs.getInt("width"), rs.getInt("height")}, assetId);
        assertThat(dims).containsExactly(64, 48);
        // EXIF 检测必须命中标准 APP1 布局并留下 PASSED 审计（剥离而非拒绝）
        assertThat(countScan(assetId, "EXIF_STRIP", "PASSED"))
                .as("EXIF 应被检测到并以审计留痕方式剥离").isEqualTo(1);
        assertThat(countScan(assetId, "EXIF_STRIP", "REJECTED")).isZero();
        Long storedSize = jdbc.queryForObject("SELECT stored_size FROM asset WHERE id = ?", Long.class, assetId);
        Long objectSize = (long) storage.getObject(objectKey).readAllBytes().length;
        assertThat(storedSize).as("stored_size 与实际入库对象一致").isEqualTo(objectSize);
        String storedSha = jdbc.queryForObject("SELECT stored_sha256 FROM asset WHERE id = ?", String.class, assetId);
        assertThat(storedSha).isEqualTo(sha256(storage.getObject(objectKey).readAllBytes()));
    }

    // ========== 2. 伪 MIME：声明 PNG 实际 JPEG ==========

    @Test
    void fakeMimeRejected() throws Exception {
        byte[] jpeg = jpegBytes(32, 32);
        long assetId = uploadAndComplete(USER_A, "USER_SKETCH", "image/png", jpeg);
        assertThat(assetStatus(assetId)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT rejected_reason FROM asset WHERE id = ?",
                String.class, assetId)).contains("MAGIC_NUMBER");
        assertThat(countScan(assetId, "MAGIC_NUMBER", "REJECTED")).isEqualTo(1);
    }

    // ========== 3. 大小超限在票据阶段拒绝 ==========

    @Test
    void oversizeUploadTicketRefused() {
        byte[] tiny = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        assertThatThrownBy(() -> assetService.createUploadTicket(USER_A, "USER_SKETCH",
                "image/png", 11L * 1024 * 1024, sha256(tiny)))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_071_000_001));
        // PDF 不允许作为用户草图
        assertThatThrownBy(() -> assetService.createUploadTicket(USER_A, "USER_SKETCH",
                "application/pdf", 1024, sha256(tiny)))
                .isInstanceOf(ServiceException.class);
    }

    // ========== 4. 像素守卫：2 万 × 2 万 PNG 头，解码前拦截 ==========

    @Test
    void pixelBombRejectedBeforeDecode() {
        byte[] bomb = new byte[64];
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, bomb, 0, 8);
        bomb[8] = 0; bomb[9] = 0; bomb[10] = 0; bomb[11] = 13; // IHDR 长度
        bomb[12] = 'I'; bomb[13] = 'H'; bomb[14] = 'D'; bomb[15] = 'R';
        int w = 20000, h = 20000;
        bomb[16] = (byte) (w >>> 24); bomb[17] = (byte) (w >>> 16);
        bomb[18] = (byte) (w >>> 8); bomb[19] = (byte) w;
        bomb[20] = (byte) (h >>> 24); bomb[21] = (byte) (h >>> 16);
        bomb[22] = (byte) (h >>> 8); bomb[23] = (byte) h;

        long assetId = uploadAndComplete(USER_A, "USER_SKETCH", "image/png", bomb);
        assertThat(assetStatus(assetId)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT rejected_reason FROM asset WHERE id = ?",
                String.class, assetId)).contains("PIXEL_GUARD");
    }

    // ========== 5. 恶意样本（EICAR）拒绝 ==========

    @Test
    void eicarSampleRejected() {
        String eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
        byte[] content = eicar.getBytes(StandardCharsets.US_ASCII);
        long assetId = uploadAndComplete(USER_A, "USER_SKETCH", "image/png", content);
        assertThat(assetStatus(assetId)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT rejected_reason FROM asset WHERE id = ?",
                String.class, assetId)).contains("MALWARE");
    }

    // ========== 6. SHA 不一致拒绝 ==========

    @Test
    void shaMismatchRejected() throws Exception {
        byte[] png = pngBytes(16, 16);
        var ticket = assetService.createUploadTicket(USER_A, "USER_SKETCH", "image/png",
                png.length, sha256(png));
        String objectKey = ticket.uploadUrl().substring("local://".length());
        byte[] tampered = java.util.Arrays.copyOf(png, png.length);
        tampered[tampered.length - 1] ^= 0x55;
        storage.putObject(objectKey, tampered);
        long assetId = ticket.assetId();
        assertThat(assetService.completeUpload(USER_A, assetId)).isEqualTo("REJECTED");
        assertThat(assetStatus(assetId)).isEqualTo("REJECTED");
    }

    // ========== 7. 对象级权限与公开授权 ==========

    @Test
    void objectLevelAuthAndPublicGrantLifecycle() throws Exception {
        byte[] png = pngBytes(8, 8);
        long assetId = uploadAndComplete(USER_A, "USER_SKETCH", "image/png", png);

        // B 无授权：下载票据拒绝
        assertThatThrownBy(() -> assetService.requestDownloadTicket(USER_B, assetId))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_099_000_002));

        // 仅公开展示 ≠ 生成参考
        assertThat(rightsGrantService.hasEffectiveGrant(assetId, "GENERATION_REFERENCE")).isFalse();
        long grantId = rightsGrantService.createGrant(USER_A, assetId, "PUBLIC_DISPLAY",
                "用户A", "*", "*", Instant.now(), null);
        assertThat(rightsGrantService.hasEffectiveGrant(assetId, "PUBLIC_DISPLAY")).isTrue();
        assertThat(rightsGrantService.hasEffectiveGrant(assetId, "GENERATION_REFERENCE"))
                .as("仅公开不可生成").isFalse();

        // B 可申请票据并换取下载 URL
        var ticket = assetService.requestDownloadTicket(USER_B, assetId);
        String url = assetService.resolveDownloadTicket(USER_B, assetId, ticket.getToken());
        assertThat(url).startsWith("local://");

        // 撤回后立即拒绝（读链路实时判定）
        assertThat(rightsGrantService.withdraw(grantId, "admin")).isTrue();
        assertThatThrownBy(() -> assetService.requestDownloadTicket(USER_B, assetId))
                .isInstanceOf(ServiceException.class);
    }

    // ========== 8. 票据与资产绑定 ==========

    @Test
    void downloadTicketBoundToAsset() throws Exception {
        byte[] png = pngBytes(8, 8);
        long asset1 = uploadAndComplete(USER_A, "USER_SKETCH", "image/png", png);
        long asset2 = uploadAndComplete(USER_A, "USER_SKETCH", "image/png", png);

        var ticket = assetService.requestDownloadTicket(USER_A, asset1);
        assertThatThrownBy(() -> assetService.resolveDownloadTicket(USER_A, asset2, ticket.getToken()))
                .isInstanceOf(ServiceException.class);
    }

    // ========== 9. 幂等：重复 complete 返回 ACCEPTED ==========

    @Test
    void completeUploadIsIdempotent() throws Exception {
        byte[] png = pngBytes(8, 8);
        long assetId = uploadAndComplete(USER_A, "USER_SKETCH", "image/png", png);
        assertThat(assetService.completeUpload(USER_A, assetId)).isEqualTo("ACCEPTED");
        assertThat(assetService.completeUpload(USER_A, assetId)).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_scan_result WHERE asset_id = ?",
                Integer.class, assetId)).as("首次记录审核证据，重放不重复记扫描").isEqualTo(1);
    }

    private int countScan(long assetId, String scanType, String status) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM asset_scan_result WHERE asset_id = ? AND scan_type = ? AND status = ?",
                Integer.class, assetId, scanType, status);
        return n == null ? 0 : n;
    }

}
