package cn.iocoder.yudao.server.dev;

import cn.iocoder.yudao.module.commerce.payment.RechargePaymentService;
import cn.iocoder.yudao.module.design.budget.BudgetService;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

/**
 * 开发/联调种子数据（zhongshu.design.seed-dev-data=true 时执行，存在即跳过）
 * 生产严禁开启该开关。
 */
@Slf4j
@Component
@Profile("zsdev & !prod & !production")
public class DevDataSeeder implements org.springframework.beans.factory.InitializingBean {

    private final JdbcTemplate jdbcTemplate;
    private final BudgetService budgetService;
    private final RechargePaymentService paymentService;
    private final AccessCodeService accessCodeService;
    private final CaseCatalogService caseCatalogService;
    private final cn.iocoder.yudao.module.design.asset.ObjectStoragePort storage;
    private final cn.iocoder.yudao.module.design.rights.RightsGrantService rights;
    private final org.springframework.transaction.support.TransactionTemplate seedTransaction;

    @Value("${zhongshu.design.seed-dev-data:false}")
    private boolean enabled;

    public DevDataSeeder(DataSource dataSource, BudgetService budgetService,
                         RechargePaymentService paymentService, AccessCodeService accessCodeService,
                         CaseCatalogService caseCatalogService,
                         cn.iocoder.yudao.module.design.asset.ObjectStoragePort storage) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.budgetService = budgetService;
        this.paymentService = paymentService;
        this.accessCodeService = accessCodeService;
        this.caseCatalogService = caseCatalogService;
        this.storage = storage;
        this.rights = new cn.iocoder.yudao.module.design.rights.RightsGrantService(dataSource);
        this.seedTransaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
    }

    public void afterPropertiesSet() {
        if (!enabled) {
            return;
        }
        // InitializingBean runs before AOP; use an explicit transaction, not @Transactional.
        seedTransaction.executeWithoutResult(status -> {
            jdbcTemplate.execute("SELECT pg_advisory_xact_lock(9013008)");
            initializeSeed();
        });
    }

    private void initializeSeed() {
        Integer seeded = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM recharge_plan WHERE deleted = FALSE", Integer.class);
        if (seeded != null && seeded > 0) {
            seedDemoCase(); // Repairs the former partially committed seed missing display rights.
            seedAccessCode();
            return;
        }
        jdbcTemplate.update("INSERT INTO generation_price_rule (id, stage, unit_point_cost, min_count, "
                + "max_count, effective_at) VALUES (900001, 'FLAT', 10, 1, 4, now())");
        jdbcTemplate.update("INSERT INTO generation_price_rule (id, stage, unit_point_cost, min_count, "
                + "max_count, effective_at) VALUES (900002, 'ELEVATION', 15, 1, 4, now())");
        paymentService.createPlan("体验包 50 元", 5000, 500, 50, true, 1);
        paymentService.createPlan("标准包 100 元", 10000, 1000, 200, false, 2);
        paymentService.createPlan("旗舰包 300 元", 30000, 3000, 800, false, 3);
        budgetService.createRuleVersion("VAR1", "BRICK", "A", 300_00L, 500_00L, Instant.now().minusSeconds(60));
        budgetService.createRuleVersion("VAR1", "BRICK", "B", 500_00L, 800_00L, Instant.now().minusSeconds(60));
        seedDemoCase();
        seedAccessCode();
        log.warn("[DevDataSeeder][开发种子数据写入完成——生产环境严禁开启 zhongshu.design.seed-dev-data]");
    }

    private void seedDemoCase() {
        long admin = 1L;
        var existing = jdbcTemplate.queryForList("SELECT c.id FROM design_case c JOIN design_case_version v ON v.id=c.current_version_id "
                        + "WHERE v.title=? AND c.source_type='COMPANY' AND c.creator_user_id=1 AND c.deleted=FALSE ORDER BY c.id LIMIT 1",
                Long.class, "云栖雅院（示例）");
        long caseId = existing.isEmpty() ? caseCatalogService.createCompanyCase(admin, "云栖雅院（示例）",
                "新中式二层自建别墅，五室三厅，带庭院", "NEW_CHINESE", 2, 168,
                12, 10, null, List.of("新中式", "庭院")) : existing.get(0);
        long versionId = jdbcTemplate.queryForObject(
                "SELECT current_version_id FROM design_case WHERE id = ?", Long.class, caseId);
        if (existing.isEmpty()) {
            long cover = putAsset("accepted/dev/demo-cover.png", 0x8B6F47);
            long plan1 = putAsset("accepted/dev/demo-plan-1.png", 0xD4C5A9);
            long plan2 = putAsset("accepted/dev/demo-plan-2.png", 0xC4B59A);
            long elev = putAsset("accepted/dev/demo-elevation.png", 0xA08060);
            insertRel(versionId, cover, "COVER", null);
            insertRel(versionId, plan1, "FLOOR_PLAN", 1);
            insertRel(versionId, plan2, "FLOOR_PLAN", 2);
            insertRel(versionId, elev, "ELEVATION", null);
        }
        for (Long assetId : jdbcTemplate.queryForList("SELECT asset_id FROM design_case_asset WHERE case_version_id=? AND deleted=FALSE", Long.class, versionId)) {
            if (!rights.hasEffectiveGrant(assetId, "PUBLIC_DISPLAY")) {
                rights.createGrant(admin, assetId, "PUBLIC_DISPLAY", "开发示例", "*", "示例展示", Instant.now().minusSeconds(1), null);
            }
        }
        String state = jdbcTemplate.queryForObject("SELECT publication_status FROM design_case WHERE id=?", String.class, caseId);
        if (!"PUBLISHED".equals(state)) caseCatalogService.publish(caseId, "dev-seeder");
        log.info("[DevDataSeeder][示例案例 {} 已发布]", caseId);
    }

    private void insertRel(long versionId, long assetId, String role, Integer floorNo) {
        jdbcTemplate.update(
                "INSERT INTO design_case_asset (id, case_version_id, asset_id, asset_role, floor_no) "
                        + "VALUES (?, ?, ?, ?, ?)",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), versionId, assetId, role, floorNo);
    }

    private long putAsset(String objectKey, int colorSeed) {
        byte[] png = tinyPng(colorSeed);
        storage.putObject(objectKey, png);
        long assetId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        String sha = sha256(png);
        jdbcTemplate.update(
                "INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, "
                        + "declared_mime, size_bytes, upload_status, security_scan_status, moderation_status, "
                        + "stored_sha256, stored_size) "
                        + "VALUES (?, ?, 1, 'CASE_IMAGE', 'COMPANY', ?, 'image/png', ?, "
                        + "'ACCEPTED', 'PASSED', 'PASSED', ?, ?)",
                assetId, objectKey, sha, (long) png.length, sha, (long) png.length);
        return assetId;
    }

    private void seedAccessCode() {
        if (jdbcTemplate.queryForObject("SELECT count(*) FROM design_access_code_batch "
                + "WHERE issued_by='dev-seeder' AND purpose_note='开发联调测试码' AND deleted=FALSE", Integer.class) > 0) return;
        var batch = accessCodeService.createBatch(1, "INLINE", 365, "开发联调测试码", "dev-seeder");
        batch.oneTimeCodes().forEach(code -> log.info("[DevDataSeeder][测试授权码: {}]", code));
    }

    private byte[] tinyPng(int colorSeed) {
        try {
            var img = new java.awt.image.BufferedImage(128, 128, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g = img.createGraphics();
            g.setColor(new java.awt.Color(colorSeed));
            g.fillRect(0, 0, 128, 128);
            g.setColor(java.awt.Color.WHITE);
            g.drawString("DEMO", 40, 64);
            g.dispose();
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String sha256(byte[] content) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
