package cn.iocoder.yudao.module.identity;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.identity.account.AccountLoginService;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeCipher;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeRedemptionService;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeService;
import cn.iocoder.yudao.module.identity.accesscode.AccessGrantService;
import cn.iocoder.yudao.module.identity.session.UserSessionService;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.JdbcDeliveryPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2A 合同测试（真实 PostgreSQL）：授权码生成熵与 HMAC、INLINE/TICKET 互斥与一次性交付、
 * 同一码 20 路并发仅一人成功、同微信幂等、过期/停用/撤销实时生效、明文不落库。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdentityP2AContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private static final String TEST_PEPPER = "p2a-test-pepper-0123456789abcdef-0123456789abcdef";
    private static final String TEST_ARTIFACT_KEY =
            Base64.getEncoder().encodeToString(new byte[]{
                    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                    16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31});

    private static final String APPID = "wx-test-appid";

    private JdbcTemplate jdbc;
    private AccessCodeCipher cipher;
    private AccessCodeService accessCodeService;
    private AccessCodeRedemptionService redemptionService;
    private AccountLoginService loginService;
    private UserSessionService sessionService;
    private AccessGrantService grantService;

    @BeforeAll
    void setUp() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        DataSource dataSource = ds;
        jdbc = new JdbcTemplate(dataSource);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/platform", "classpath:db/migration/identity")
                .load()
                .migrate();

        cipher = AccessCodeCipher.forTesting(TEST_PEPPER, "v1", TEST_ARTIFACT_KEY);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        accessCodeService = new AccessCodeService(dataSource, txManager, cipher, new JdbcDeliveryPort(dataSource));
        loginService = new AccountLoginService(dataSource, txManager,
                new cn.iocoder.yudao.module.identity.wechat.StubWechatIdentityAdapter(),
                new UserSessionService(dataSource, txManager));
        redemptionService = new AccessCodeRedemptionService(dataSource, txManager, cipher, loginService);
        sessionService = new UserSessionService(dataSource, txManager);
        grantService = new AccessGrantService(dataSource, txManager);
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE user_session, design_access_grant, access_code_redemption, "
                + "design_access_code, design_access_code_batch, wechat_identity, account, "
                + "one_time_delivery_ticket");
    }

    private int count(String table, String where) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where,
                Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void profileChangesPersistOnlyForTheSessionAccount() {
        long owner = loginService.login(APPID, "profile-owner", null).accountId();
        long other = loginService.login(APPID, "profile-other", null).accountId();
        var service = new cn.iocoder.yudao.module.identity.account.AccountProfileService(jdbc.getDataSource(),
                (accountId, assetId) -> { assertThat(accountId).isEqualTo(owner); assertThat(assetId).isEqualTo(501L); });
        assertThat(service.update(owner, "  新昵称  ", "501")).isTrue();
        assertThat(loginService.findProfile(owner).orElseThrow().nickname()).isEqualTo("新昵称");
        assertThat(loginService.findProfile(owner).orElseThrow().avatar()).isEqualTo("asset:501");
        assertThat(service.update(owner, "再次修改", null)).isTrue();
        assertThat(loginService.findProfile(owner).orElseThrow().avatar()).isEqualTo("asset:501");
        assertThat(loginService.findProfile(other).orElseThrow().nickname()).isNotEqualTo("再次修改");
    }

    @Test
    void invalidProfileAndRejectedAvatarDoNotChangeTheAccount() {
        long owner = loginService.login(APPID, "profile-reject", null).accountId();
        var before = loginService.findProfile(owner).orElseThrow();
        var service = new cn.iocoder.yudao.module.identity.account.AccountProfileService(jdbc.getDataSource(),
                (accountId, assetId) -> { throw new org.springframework.security.access.AccessDeniedException("rejected"); });
        for (String name : List.of("", "   ", "x".repeat(33), "bad\nname")) {
            assertThatThrownBy(() -> service.update(owner, name, null)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String id : List.of("-1", "0", "https://avatar", "9999999999999999999")) {
            assertThatThrownBy(() -> service.update(owner, "昵称", id)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.update(owner, "昵称", "501")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(loginService.findProfile(owner).orElseThrow().nickname()).isEqualTo(before.nickname());
        assertThat(loginService.findProfile(owner).orElseThrow().avatar()).isEqualTo(before.avatar());
    }

    private List<String> createInlineBatch(int quantity) {
        return accessCodeService.createBatch(quantity, "INLINE", null, "test", "tester").oneTimeCodes();
    }

    // ========== 1. 熵、HMAC 与掩码合同 ==========

    @Test
    void codeGenerationEntropyAndHmacContract() throws Exception {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            codes.add(cipher.generate());
        }
        assertThat(codes).as("SecureRandom 128 bit 生成的码必须唯一").hasSize(200);
        for (String code : codes) {
            assertThat(code).startsWith("ZS-");
            assertThat(cipher.normalize(code)).hasSize(28).matches("[0-9A-Z]+");
        }

        String sample = codes.iterator().next();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_PEPPER.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expectedHmac = HexFormat.of().formatHex(
                mac.doFinal(cipher.normalize(sample).getBytes(StandardCharsets.UTF_8)));
        assertThat(cipher.hash(sample)).as("库内 hash 必须等于 HMAC-SHA-256(pepper, 规范化明文)")
                .isEqualTo(expectedHmac);

        assertThat(cipher.mask(sample)).matches("ZS-[0-9A-Z]{5}-\\*\\*\\*\\*-[0-9A-Z]{5}");
    }

    @Test
    void pepperRequired() {
        AccessCodeCipher noPepper = AccessCodeCipher.forTesting("", "v1", TEST_ARTIFACT_KEY);
        assertThatThrownBy(() -> noPepper.hash("ZS-TEST")).isInstanceOf(IllegalStateException.class);
    }

    // ========== 2. INLINE：创建即交付且唯一一次 ==========

    @Test
    void inlineBatchExposesOnceAndRejectsTicket() {
        List<String> codes = createInlineBatch(5);
        assertThat(codes).hasSize(5);
        assertThat(count("design_access_code", "secret_exposed_at IS NOT NULL")).isEqualTo(5);
        assertThat(count("design_access_code_batch", "encrypted_artifact IS NOT NULL")).isEqualTo(1);

        long batchId = jdbc.queryForObject(
                "SELECT id FROM design_access_code_batch ORDER BY id DESC LIMIT 1", Long.class);
        assertThatThrownBy(() -> accessCodeService.issueDeliveryTicket(batchId, "op"))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_070_000_004));

        // 明文不落库：任何表都不含明文
        for (String code : codes) {
            assertThat(count("design_access_code", "code_hash = '" + code + "'")).isZero();
            assertThat(count("design_access_code_batch", "issued_by = '" + code + "'")).isZero();
        }
    }

    @Test
    void unusedCodeCanBeCopiedAgainButConsumedOrDeletedCodeCannot() {
        List<String> codes = createInlineBatch(2);
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM design_access_code ORDER BY id", Long.class);

        assertThat(accessCodeService.copyActiveCode(ids.get(0), "admin-7")).isEqualTo(codes.get(0));
        assertThat(accessCodeService.deleteUnusedCode(ids.get(1), "admin-7")).isTrue();
        assertThatThrownBy(() -> accessCodeService.copyActiveCode(ids.get(1), "admin-7"))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_070_000_000));
        assertThat(count("design_access_code", "id = " + ids.get(1) + " AND deleted = TRUE AND status = 'DISABLED'"))
                .isEqualTo(1);
    }

    @Test
    void copiedCodeMustBeUnusedAndNotExpired() {
        List<String> codes = createInlineBatch(2);
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM design_access_code ORDER BY id", Long.class);
        redemptionService.redeem(APPID, "openid-copy-used", null, codes.get(0));

        assertThatThrownBy(() -> accessCodeService.copyActiveCode(ids.get(0), "admin-7"))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_070_000_000));

        jdbc.update("UPDATE design_access_code SET expires_at = now() - interval '1 second' WHERE id = ?", ids.get(1));
        assertThatThrownBy(() -> accessCodeService.copyActiveCode(ids.get(1), "admin-7"))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_070_000_001));
    }

    // ========== 3. TICKET：票据单次消费、重放必败，授权码密文供未使用码复制 ==========

    @Test
    void ticketDeliveryIsExactlyOnceAndCodeArtifactRemainsEncrypted() throws Exception {
        AccessCodeService.BatchCreateResult batch = accessCodeService.createBatch(
                5, "TICKET", null, "ticket-test", "tester");
        assertThat(batch.oneTimeCodes()).isEmpty();

        var ticket = accessCodeService.issueDeliveryTicket(batch.batchId(), "tester");
        assertThat(ticket.getToken()).isNotBlank();
        var secondTicket = accessCodeService.issueDeliveryTicket(batch.batchId(), "tester");
        assertThat(secondTicket.getToken()).isNotBlank();

        // 并发消费同一票据：恰好一次成功交付
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<String>>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return accessCodeService.exportByTicket(batch.batchId(), ticket.getToken(), "op");
            }));
        }
        start.countDown();
        int success = 0;
        List<String> delivered = null;
        for (Future<List<String>> f : futures) {
            try {
                delivered = f.get();
                success++;
            } catch (Exception e) {
                // 失败方：ALREADY_CONSUMED
            }
        }
        pool.shutdownNow();
        assertThat(success).as("同一票据并发消费只有一次交付").isEqualTo(1);
        assertThat(delivered).hasSize(5);

        // 票据已消费，整批重放被拒；服务端密文保留以支持之后复制仍未使用的单码
        assertThat(count("design_access_code_batch", "encrypted_artifact IS NOT NULL")).isEqualTo(1);
        assertThatThrownBy(() -> accessCodeService.exportByTicket(batch.batchId(), ticket.getToken(), "op"))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_070_000_004));
        assertThatThrownBy(() -> accessCodeService.exportByTicket(batch.batchId(), secondTicket.getToken(), "op"))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_070_000_004));

        // 明文已交付后再签票据被拒
        assertThatThrownBy(() -> accessCodeService.issueDeliveryTicket(batch.batchId(), "tester"))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_070_000_004));
    }

    // ========== 4. 同一码 20 路并发仅一人成功 ==========

    @Test
    void twentyConcurrentRedemptionsSingleWinner() throws Exception {
        List<String> codes = createInlineBatch(20);
        String targetCode = codes.get(0);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int seq = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        redemptionService.redeem(APPID, "openid-racer-" + seq, null, targetCode);
                        return true;
                    } catch (ServiceException e) {
                        assertThat(e.getCode()).isEqualTo(1_070_000_003);
                        return false;
                    }
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> f : futures) {
                if (f.get()) {
                    winners++;
                }
            }
            assertThat(winners).as("同一码 20 路并发兑换只有一人成功").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("design_access_code", "status = 'CONSUMED'")).isEqualTo(1);
        assertThat(count("access_code_redemption", "TRUE")).isEqualTo(1);
        assertThat(count("design_access_grant", "status = 'ACTIVE'")).isEqualTo(1);
    }

    // ========== 5. 同微信重复兑换幂等；他人兑换被拒 ==========

    @Test
    void sameWechatRedeemIdempotentOtherWechatRejected() {
        List<String> codes = createInlineBatch(3);
        String openid = "openid-idem-1";

        var first = redemptionService.redeem(APPID, openid, null, codes.get(0));
        var second = redemptionService.redeem(APPID, openid, null, codes.get(0));
        assertThat(second.grantId()).as("同微信同码幂等返回同一授权").isEqualTo(first.grantId());
        assertThat(count("design_access_grant", "status = 'ACTIVE'")).isEqualTo(1);

        // 同微信换新码：可再兑换（授权仍一条）
        redemptionService.redeem(APPID, openid, null, codes.get(1));
        assertThat(count("design_access_grant", "status = 'ACTIVE'")).isEqualTo(1);

        // 他人兑换已被消费的码 → ALREADY_CONSUMED
        assertThatThrownBy(() -> redemptionService.redeem(APPID, "openid-other", null, codes.get(0)))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_070_000_003));
    }

    // ========== 6. 过期与停用 ==========

    @Test
    void expiredAndDisabledCodesRejected() {
        List<String> codes = createInlineBatch(2);

        jdbc.execute("UPDATE design_access_code SET expires_at = now() - interval '1 hour' "
                + "WHERE code_mask = '" + cipher.mask(codes.get(0)) + "'");
        assertThatThrownBy(() -> redemptionService.redeem(APPID, "openid-exp", null, codes.get(0)))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_070_000_001));

        long secondId = jdbc.queryForObject(
                "SELECT id FROM design_access_code WHERE code_mask = ?",
                Long.class, cipher.mask(codes.get(1)));
        assertThat(accessCodeService.disableCode(secondId)).isTrue();
        assertThatThrownBy(() -> redemptionService.redeem(APPID, "openid-dis", null, codes.get(1)))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_070_000_002));
    }

    // ========== 7. 受限会话与授权实时升降级 ==========

    @Test
    void sessionRestrictedUntilRedeemAndRevokedInRealtime() {
        String loginCode = "login-code-realtime";
        var login = loginService.login(APPID, loginCode, null);
        assertThat(login.restricted()).as("未兑换时受限").isTrue();

        var context = sessionService.validateAccessToken(login.accessToken()).orElseThrow();
        assertThat(context.restricted()).isTrue();

        // 用同一身份兑换（stub：同 loginCode → 同 openid）
        List<String> codes = createInlineBatch(1);
        redemptionService.redeem(APPID, login.openid(), null, codes.get(0));

        var after = sessionService.validateAccessToken(login.accessToken()).orElseThrow();
        assertThat(after.restricted()).as("兑换后同一会话实时升级为正式").isFalse();

        // 后台解绑 → 会话实时降级为受限（token 未变）
        long grantId = jdbc.queryForObject(
                "SELECT id FROM design_access_grant WHERE status = 'ACTIVE'", Long.class);
        assertThat(grantService.revoke(grantId, "admin-1")).isTrue();
        var revoked = sessionService.validateAccessToken(login.accessToken()).orElseThrow();
        assertThat(revoked.restricted()).as("授权撤销实时生效").isTrue();

        // 旧码永不复活：撤销后再次兑换同一码，对该微信仍幂等、对他人仍拒绝
        redemptionService.redeem(APPID, login.openid(), null, codes.get(0));
        assertThatThrownBy(() -> redemptionService.redeem(APPID, "openid-z", null, codes.get(0)))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void expiredAccessCanRefreshAndRotationRevokesBothOldTokens() {
        var login = loginService.login(APPID, "refresh-expired-access", null);
        jdbc.update("UPDATE user_session SET expires_at = now() - interval '1 minute'");
        assertThat(sessionService.validateAccessToken(login.accessToken())).isEmpty();
        var rotated = sessionService.refresh(login.refreshToken()).orElseThrow();
        assertThat(rotated.restricted()).isTrue();
        assertThat(sessionService.validateAccessToken(rotated.accessToken())).isPresent();
        assertThat(sessionService.refresh(login.refreshToken())).isEmpty();
        assertThat(sessionService.refreshExpiresAt(rotated.refreshToken()).orElseThrow())
                .isAfter(Instant.now().plusSeconds(29 * 24 * 3600L));
    }

    @Test
    void refreshExpiryAndAccountClosureRejectWithoutIssuingTokens() {
        var login = loginService.login(APPID, "refresh-expiry", null);
        jdbc.update("UPDATE user_session SET refresh_expires_at = now() - interval '1 second'");
        assertThat(sessionService.refresh(login.refreshToken())).isEmpty();
        jdbc.update("UPDATE user_session SET refresh_expires_at = now() + interval '1 day'");
        jdbc.update("UPDATE account SET status = 'CLOSED'");
        assertThat(sessionService.refresh(login.refreshToken())).isEmpty();
        assertThat(sessionService.refresh(null)).isEmpty();
        assertThat(sessionService.refresh(" ")).isEmpty();
        assertThat(count("user_session", "TRUE")).isEqualTo(1);
    }

    @Test
    void closedAccountInvalidatesExistingAccessToken() {
        // 停用账号（CLOSED）后，尚未过期的既有 access token 必须立即失效——由 validateAccessToken 的 INNER JOIN account a.status='ACTIVE' 保障，
        // 与上一用例的 refresh 拒绝路径互补，闭合「停用账号」在 validateAccessToken 侧的实时失效验证（T13-08 §5）
        var login = loginService.login(APPID, "closed-access-token", null);
        assertThat(sessionService.validateAccessToken(login.accessToken())).as("停用前既有 access token 有效").isPresent();
        jdbc.update("UPDATE account SET status = 'CLOSED'");
        assertThat(sessionService.validateAccessToken(login.accessToken())).as("账号停用后既有 access token 立即失效").isEmpty();
        assertThat(count("user_session", "TRUE")).isEqualTo(1);
    }

    @Test
    void refreshResponseRechecksGrantAfterActivationAndRevocation() {
        var login = loginService.login(APPID, "refresh-grant", null);
        redemptionService.redeem(APPID, login.openid(), null, createInlineBatch(1).get(0));
        var granted = sessionService.refresh(login.refreshToken()).orElseThrow();
        assertThat(granted.restricted()).isFalse();
        long grantId = jdbc.queryForObject("SELECT id FROM design_access_grant WHERE status = 'ACTIVE'", Long.class);
        grantService.revoke(grantId, "admin");
        var restricted = sessionService.refresh(granted.refreshToken()).orElseThrow();
        assertThat(restricted.restricted()).isTrue();
        assertThat(sessionService.validateAccessToken(restricted.accessToken()).orElseThrow().restricted()).isTrue();
    }

    @Test
    void concurrentRefreshHasExactlyOneWinner() throws Exception {
        var login = loginService.login(APPID, "refresh-concurrent", null);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                start.await();
                return sessionService.refresh(login.refreshToken()).isPresent();
            }));
            start.countDown();
            int winners = 0;
            for (Future<Boolean> future : futures) if (future.get(30, java.util.concurrent.TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("user_session", "revoked_at IS NULL")).isEqualTo(1);
        assertThat(count("user_session", "TRUE")).isEqualTo(2);
    }

    // ========== 8. 跨 AppID 隔离与 token 不落库 ==========

    @Test
    void crossAppidCreatesSeparateAccountAndTokenNeverStored() {
        var loginA = loginService.login("wx-appid-a", "same-login-code", null);
        var loginB = loginService.login("wx-appid-b", "same-login-code", null);
        assertThat(loginA.accountId()).as("不同 AppID 是不同身份/账号").isNotEqualTo(loginB.accountId());
        assertThat(count("account", "TRUE")).isEqualTo(2);

        assertThat(loginA.accessToken()).isNotEqualTo(loginA.refreshToken());
        assertThat(count("user_session", "token_hash = '" + loginA.accessToken() + "'")).isZero();
        assertThat(count("user_session", "refresh_token_hash = '" + loginA.refreshToken() + "'")).isZero();
        assertThat(count("user_session", "TRUE")).isEqualTo(2);
    }

}
