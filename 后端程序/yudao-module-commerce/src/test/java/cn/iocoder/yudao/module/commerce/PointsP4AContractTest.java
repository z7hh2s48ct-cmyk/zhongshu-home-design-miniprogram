package cn.iocoder.yudao.module.commerce;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.commerce.adjustment.ManualPointAdjustmentService;
import cn.iocoder.yudao.module.commerce.pricing.PriceRuleQuote;
import cn.iocoder.yudao.module.commerce.pricing.PriceRuleService;
import cn.iocoder.yudao.module.commerce.points.PointAccountService;
import cn.iocoder.yudao.module.infra.zhongshu.audit.JdbcAuditPort;
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

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P4A 合同测试（真实 PostgreSQL）：点数账本并发正确性、幂等、双人调点与计价快照
 *
 * 覆盖蓝图验收：余额不足、并发扣点、扣点与退款预留并发、价格更新竞态、
 * available+reserved=流水总余额 不变量、重复执行、同人复核拒绝、反向纠错不删原记录。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PointsP4AContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private JdbcTemplate jdbc;
    private PointAccountService points;
    private PriceRuleService priceRules;
    private ManualPointAdjustmentService adjustments;

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
                .locations("classpath:db/migration/platform", "classpath:db/migration/commerce")
                .load()
                .migrate();

        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        points = new PointAccountService(dataSource, txManager);
        priceRules = new PriceRuleService(dataSource);
        adjustments = new ManualPointAdjustmentService(dataSource, txManager, points, new JdbcAuditPort(dataSource));
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE generation_price_rule, design_point_account, design_point_ledger, "
                + "manual_point_adjustment");
    }

    private long balanceTotal(long userId) {
        var acc = points.findAccount(userId);
        return acc.map(a -> a.availablePoints() + a.reservedPoints()).orElse(0L);
    }

    private long ledgerSum(long userId) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(delta), 0) FROM design_point_ledger WHERE user_id = ?",
                Long.class, userId);
        return sum == null ? 0 : sum;
    }

    private int ledgerCount(long userId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM design_point_ledger WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    /** 并发执行：返回成功个数 */
    private int runConcurrent(int threads, List<Callable<Boolean>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (Callable<Boolean> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            int ok = 0;
            for (Future<Boolean> f : futures) {
                if (f.get()) {
                    ok++;
                }
            }
            return ok;
        } finally {
            pool.shutdownNow();
        }
    }

    // ========== 1. 基本出入账与不变量 ==========

    @Test
    void creditThenDebitKeepsLedgerInvariant() {
        points.credit(1L, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "r1", "k-credit-1", null, null);
        points.debit(1L, "FLAT_GENERATION_DEBIT", 30, "ai_job", "j1", "k-debit-1", null, null);

        assertThat(points.findAccount(1L).orElseThrow().availablePoints()).isEqualTo(70);
        assertThat(ledgerCount(1L)).isEqualTo(2);
        assertThat(balanceTotal(1L)).as("总余额 = Σ流水").isEqualTo(ledgerSum(1L));
    }

    @Test
    void insufficientBalanceThrowsStableCodeAndChangesNothing() {
        points.credit(2L, "RECHARGE_BASE_CREDIT", 10, "recharge_order", "r2", "k-credit-2", null, null);
        assertThatThrownBy(() -> points.debit(2L, "ELEVATION_GENERATION_DEBIT", 999,
                "ai_job", "j2", "k-debit-2", null, null))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_072_000_000));
        assertThat(points.findAccount(2L).orElseThrow().availablePoints()).isEqualTo(10);
        assertThat(ledgerCount(2L)).isEqualTo(1);
    }

    // ========== 2. 并发扣点：只花得动账户里有的 ==========

    @Test
    void concurrentDebitsNeverOverdraw() throws Exception {
        points.credit(3L, "RECHARGE_BASE_CREDIT", 50, "recharge_order", "r3", "k-credit-3", null, null);
        int threads = 10;
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int seq = i;
            tasks.add(() -> {
                try {
                    points.debit(3L, "FLAT_GENERATION_DEBIT", 10, "ai_job", "j3-" + seq,
                            "k-debit-3-" + seq, null, null);
                    return true;
                } catch (ServiceException e) {
                    return false;
                }
            });
        }
        int ok = runConcurrent(threads, tasks);
        assertThat(ok).as("余额 50 只够 5 笔 10 点扣减").isEqualTo(5);
        assertThat(points.findAccount(3L).orElseThrow().availablePoints()).isZero();
        assertThat(ledgerCount(3L)).isEqualTo(6);
        assertThat(balanceTotal(3L)).isEqualTo(ledgerSum(3L));
    }

    // ========== 3. 扣点与退款预留并发：总余额守恒 ==========

    @Test
    void concurrentDebitAndReserveConserveTotalBalance() throws Exception {
        points.credit(4L, "RECHARGE_BASE_CREDIT", 50, "recharge_order", "r4", "k-credit-4", null, null);
        int threads = 10;
        // 成功扣减的笔数单独计数：reserve 只搬动桶位（总额不变），只有 debit 真正消耗点数。
        AtomicInteger successfulDebits = new AtomicInteger();
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int seq = i;
            final boolean isDebit = i % 2 == 0;
            tasks.add(() -> {
                try {
                    if (isDebit) {
                        points.debit(4L, "FLAT_GENERATION_DEBIT", 10, "ai_job", "j4-" + seq,
                                "k-debit-4-" + seq, null, null);
                        // 事务已提交，效果确定，方可计入
                        successfulDebits.incrementAndGet();
                    } else {
                        points.reserve(4L, 10, "refund_order", "rf4-" + seq);
                    }
                    return true;
                } catch (ServiceException e) {
                    return false;
                }
            });
        }
        int ok = runConcurrent(threads, tasks);
        assertThat(ok).as("总供给 50，成功笔数不超过 5").isLessThanOrEqualTo(5);
        var acc = points.findAccount(4L).orElseThrow();
        // 每笔成功 debit 消耗 10 点（available 减少且不回到 reserved），每笔 reserve 只是
        // available→reserved 搬动（总额不变）。故总余额 = 50 - 10 × 成功扣减笔数，
        // 与线程竞速无关、恒成立；原先写死 50 只在「零扣减成功」时成立，故为偶发。
        assertThat(acc.availablePoints() + acc.reservedPoints()).as("扣减+预留并发下总余额守恒")
                .isEqualTo(50 - 10L * successfulDebits.get());
        assertThat(balanceTotal(4L)).isEqualTo(ledgerSum(4L));
    }

    /**
     * 上一条并发用例的结果取决于线程调度：本地常见「预留全胜、零扣减」，CI 上则出现过
     * 「1 笔扣减胜出、总余额 40」。为把不变量本身钉死（而非依赖跑出来的是哪种结果），
     * 这里用串行步骤确定性地走一遍「扣减胜出」的路径。
     */
    @Test
    void debitThenReservesConserveTotalBalanceDeterministically() {
        points.credit(9L, "RECHARGE_BASE_CREDIT", 50, "recharge_order", "r9", "k-credit-9", null, null);
        points.debit(9L, "FLAT_GENERATION_DEBIT", 10, "ai_job", "j9", "k-debit-9", null, null);
        points.reserve(9L, 10, "refund_order", "rf9-1");
        points.reserve(9L, 10, "refund_order", "rf9-2");

        var acc = points.findAccount(9L).orElseThrow();
        // 50 -（1 笔成功扣减 × 10）= 40；两笔预留只是把 20 从 available 搬到 reserved。
        assertThat(acc.availablePoints()).isEqualTo(20);
        assertThat(acc.reservedPoints()).isEqualTo(20);
        assertThat(acc.availablePoints() + acc.reservedPoints())
                .as("扣减胜出路径下总余额守恒").isEqualTo(40);
        assertThat(balanceTotal(9L)).isEqualTo(ledgerSum(9L));
    }

    // ========== 4. 幂等键重放（串行 + 并发） ==========

    @Test
    void idempotencyKeyReplayDoesNotDoubleCharge() {
        points.credit(5L, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "r5", "k-credit-5", null, null);
        long first = points.debit(5L, "FLAT_GENERATION_DEBIT", 20, "ai_job", "j5", "k-debit-5", null, null);
        long second = points.debit(5L, "FLAT_GENERATION_DEBIT", 20, "ai_job", "j5", "k-debit-5", null, null);
        assertThat(second).isEqualTo(first);
        assertThat(points.findAccount(5L).orElseThrow().availablePoints())
                .as("同一幂等键只扣一次").isEqualTo(80);
        assertThat(ledgerCount(5L)).isEqualTo(2);
    }

    @Test
    void concurrentSameIdempotencyKeyChargesOnce() throws Exception {
        points.credit(6L, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "r6", "k-credit-6", null, null);
        int threads = 5;
        List<Callable<Boolean>> tasks = new ArrayList<>();
        List<Long> ledgerIds = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                long ledgerId = points.debit(6L, "FLAT_GENERATION_DEBIT", 20, "ai_job", "j6",
                        "k-debit-6-same", null, null);
                ledgerIds.add(ledgerId);
                return true;
            });
        }
        runConcurrent(threads, tasks);
        assertThat(ledgerIds).as("并发同键返回同一流水").hasSize(threads)
                .containsOnly(ledgerIds.get(0));
        assertThat(points.findAccount(6L).orElseThrow().availablePoints()).isEqualTo(80);
        assertThat(ledgerCount(6L)).isEqualTo(2);
    }

    // ========== 5. 预留不写流水、总余额不变 ==========

    @Test
    void reserveAndReleaseDoNotWriteLedger() {
        points.credit(7L, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "r7", "k-credit-7", null, null);
        points.reserve(7L, 40, "refund_order", "rf7");
        var afterReserve = points.findAccount(7L).orElseThrow();
        assertThat(afterReserve.availablePoints()).isEqualTo(60);
        assertThat(afterReserve.reservedPoints()).isEqualTo(40);
        assertThat(ledgerCount(7L)).as("预留是账户内转移，不写流水").isEqualTo(1);

        points.releaseReserve(7L, 40);
        var afterRelease = points.findAccount(7L).orElseThrow();
        assertThat(afterRelease.availablePoints()).isEqualTo(100);
        assertThat(afterRelease.reservedPoints()).isZero();
        assertThat(balanceTotal(7L)).isEqualTo(ledgerSum(7L));
    }

    // ========== 6. 计价快照与价格更新竞态 ==========

    @Test
    void priceRuleQuoteSnapshotDetectsStaleness() {
        Instant now = Instant.now();
        long ruleId = priceRules.createRule("FLAT", 10, 1, 4, now.minusSeconds(60), null);

        PriceRuleQuote quote = priceRules.quote("FLAT", 2, now);
        assertThat(quote.getTotalPointCost()).isEqualTo(20);
        assertThat(quote.getRuleVersion()).isEqualTo(1);
        priceRules.validateSnapshotStillValid(quote, now); // 未变化，通过

        // 规则被停用（价格更新竞态）后，旧快照必须判定失效
        assertThat(priceRules.retireRule(ruleId)).isTrue();
        assertThatThrownBy(() -> priceRules.validateSnapshotStillValid(quote, now))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_072_000_001));

        // 新规则接力后按新规则报价
        priceRules.createRule("FLAT", 12, 1, 4, now.plusSeconds(1), null);
        assertThat(priceRules.quote("FLAT", 2, now.plusSeconds(2)).getUnitPointCost()).isEqualTo(12);
        assertThatThrownBy(() -> priceRules.quote("FLAT", 5, now.plusSeconds(2)))
                .as("数量超区间在报价时拒绝").isInstanceOf(IllegalStateException.class);
    }

    // ========== 7. 人工调点：双人复核、幂等执行、反向纠错 ==========

    @Test
    void manualAdjustmentEnforcesMakerCheckerAndIdempotentExecute() {
        long adjustmentId = adjustments.submit(8L, 50, "客服补偿", 1001L);

        // 同人复核拒绝
        assertThatThrownBy(() -> adjustments.review(adjustmentId, 1001L, true, null))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_099_000_002));

        // 异人复核通过即执行
        Long ledgerId = adjustments.review(adjustmentId, 1002L, true, "同意");
        assertThat(ledgerId).isNotNull();
        assertThat(points.findAccount(8L).orElseThrow().availablePoints()).isEqualTo(50);

        // 重复执行幂等：不重复调点
        Long again = adjustments.execute(adjustmentId);
        assertThat(again).isEqualTo(ledgerId);
        assertThat(points.findAccount(8L).orElseThrow().availablePoints()).isEqualTo(50);
        assertThat(ledgerCount(8L)).isEqualTo(1);
    }

    @Test
    void rejectedAdjustmentDoesNotTouchBalance() {
        long adjustmentId = adjustments.submit(9L, -10, "误发回收", 2001L);
        Long ledgerId = adjustments.review(adjustmentId, 2002L, false, "证据不足");
        assertThat(ledgerId).isNull();
        assertThat(points.findAccount(9L)).isEmpty();
        assertThat(ledgerCount(9L)).isZero();

        String status = jdbc.queryForObject(
                "SELECT status FROM manual_point_adjustment WHERE id = ?", String.class, adjustmentId);
        assertThat(status).isEqualTo("REJECTED");
    }

    @Test
    void executedAdjustmentCorrectedByReverseAdjustmentOnly() {
        // 先给用户入账 30，再走一笔 +50 的调点并执行
        points.credit(10L, "RECHARGE_BASE_CREDIT", 30, "recharge_order", "r10", "k-credit-10", null, null);
        long adjustmentId = adjustments.submit(10L, 50, "活动补发", 3001L);
        adjustments.review(adjustmentId, 3002L, true, null);
        assertThat(points.findAccount(10L).orElseThrow().availablePoints()).isEqualTo(80);

        // 纠错 = 新增反向调整单，原流水不可修改/删除
        long reverseId = adjustments.submit(10L, -50, "纠正误发", 3003L);
        adjustments.review(reverseId, 3004L, true, null);
        assertThat(points.findAccount(10L).orElseThrow().availablePoints()).isEqualTo(30);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM design_point_ledger WHERE user_id = 10 AND type = 'MANUAL_CREDIT'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM design_point_ledger WHERE user_id = 10 AND type = 'MANUAL_DEBIT'",
                Integer.class)).isEqualTo(1);
        assertThat(balanceTotal(10L)).isEqualTo(ledgerSum(10L));
    }

    // ========== 8. 查询合同 ==========

    @Test
    void directAdjustmentRetryUsesOneLedgerAndRejectsChangedPayload() {
        long first = adjustments.submitAndExecute(81L, 40L, "correction", 700L, "request-81");
        long replay = adjustments.submitAndExecute(81L, 40L, "correction", 700L, "request-81");
        assertThat(replay).isEqualTo(first);
        assertThat(points.findAccount(81L).orElseThrow().availablePoints()).isEqualTo(40L);
        assertThat(ledgerCount(81L)).isEqualTo(1);
        assertThatThrownBy(() -> adjustments.submitAndExecute(81L, 41L, "correction", 700L, "request-81"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ledgerCount(81L)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM manual_point_adjustment WHERE maker_user_id=700",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentDirectAdjustmentRetrySettlesExactlyOnce() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Long> request = () -> {
                start.await();
                return adjustments.submitAndExecute(82L, -10L, "correction", 701L, "request-82");
            };
            points.credit(82L, "RECHARGE_BASE_CREDIT", 20L, "recharge_order", "82", "seed-82", null, null);
            Future<Long> one = executor.submit(request);
            Future<Long> two = executor.submit(request);
            start.countDown();
            assertThat(one.get()).isEqualTo(two.get());
            assertThat(points.findAccount(82L).orElseThrow().availablePoints()).isEqualTo(10L);
            assertThat(ledgerCount(82L)).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void ledgerPageQueryReturnsNewestFirst() {
        points.credit(11L, "RECHARGE_BASE_CREDIT", 10, "recharge_order", "r11", "k-credit-11", null, null);
        points.credit(11L, "RECHARGE_BONUS_CREDIT", 5, "recharge_order", "r11", "k-credit-11b", null, null);
        points.debit(11L, "FLAT_GENERATION_DEBIT", 3, "ai_job", "j11", "k-debit-11", null, null);

        var page = points.pageLedger(11L, null, 1, 10);
        assertThat(page).hasSize(3);
        assertThat(page.get(0).type()).as("最新流水在前").isEqualTo("FLAT_GENERATION_DEBIT");
        assertThat(points.countLedger(11L, null)).isEqualTo(3);
        assertThat(points.countLedger(11L, "RECHARGE_BONUS_CREDIT")).isEqualTo(1);

        var empty = points.pageLedger(12L, null, 1, 10);
        assertThat(empty).isEmpty();
    }

}
