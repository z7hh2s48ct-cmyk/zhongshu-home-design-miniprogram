package cn.iocoder.yudao.module.identity;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeCipher;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeRedemptionService;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeRedemptionService.RedemptionResult;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeService;
import cn.iocoder.yudao.module.identity.account.AccountLoginService;
import cn.iocoder.yudao.module.identity.account.AccountLoginService.LoginResult;
import cn.iocoder.yudao.module.identity.session.UserSessionService;
import cn.iocoder.yudao.module.identity.wechat.StubWechatIdentityAdapter;
import cn.iocoder.yudao.module.identity.wechat.WechatIdentityPort;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T13-06 账号解析与激活码并发/归属回归（真实 PostgreSQL）。
 *
 * <p>验收对齐（计划 §B1 T13-06）：
 * <ul>
 *   <li><b>并发首登不重复建账号</b>：同一 (appid, openid) 多路并发首登只落一个账号，靠
 *       {@code uk_wechat_identity_appid_openid} 唯一键 + {@link AccountLoginService#resolveAccount} 的
 *       {@code DuplicateKeyException} 复用路径；</li>
 *   <li><b>同一微信重新登录归属一致</b>：同一 (appid, openid) 由不同 login code 解析仍归属同一账号；
 *       相同 openid 在不同 appid 下是不同账号；</li>
 *   <li><b>激活流程并发幂等</b>：多路 login+redeem 同一码只落一个账号、一条有效授权、一条兑换事实；</li>
 *   <li><b>兑换服务并发解析账号</b>：{@link AccessCodeRedemptionService#redeem} 作为 public 服务方法，
 *       即使不经登录、由同一新微信并发直兑不同码，也须只落一个账号、一条有效授权且全部成功。</li>
 * </ul>
 *
 * <p><b>确定性锁定 NESTED 传播（codex round-1/round-2）</b>：账号解析在 redeem 外层事务内的并发正确性依赖
 * {@code resolveAccount} 用 {@code PROPAGATION_NESTED}（JDBC savepoint）。仅靠并发时序无法确定性证明——竞争窗口内
 * 可能所有 worker 都走「已存在」快路径而不触发冲突。故本类以三个<b>确定性</b>用例锁定，唯 NESTED 全部通过：
 * <ul>
 *   <li>{@link #nestedCollisionInsideOuterTransactionKeepsOuterAlive()}：外层事务内确定性触发唯一键冲突后，外层事务仍
 *       存活——排除 {@code REQUIRED}（冲突会中止整个共享事务，PostgreSQL SQLSTATE 25P02，令 resolveAccount catch 内的
 *       重查先行失败，根本到不了外层后续语句）；</li>
 *   <li>{@link #freshIdentityRedeemingOthersConsumedCodePersistsNoAccount()}：被拒兑换的新建账号随外层事务回滚、不落库
 *       ——排除 {@code REQUIRES_NEW}（独立事务会提前提交，留下孤儿账号）；</li>
 *   <li>{@link #loserRecoversToCommittedWinnerAccountInsideOuterTransaction()}：以双连接编排确定性驱动「冲突 → 重查 →
 *       返回并发赢家账号」的恢复主干，并验证恢复后外层事务仍可用（tombstone 用例只覆盖空重查重抛分支）。</li>
 * </ul>
 * 并发用例（场景 1/3/4）在此之上补真实竞争的端到端验证。
 *
 * <p>场景「不同微信不能抢占已兑换码」「过期/撤销/已使用码返回明确结果」已由 {@code IdentityP2AContractTest}
 * 覆盖（20 路并发单赢家、他人兑换拒绝、过期/停用/撤销实时生效），本类聚焦账号解析并发与归属，避免重复。
 *
 * <p>另含一个 {@link #joinWorkersUntilDeadline} 清理助手的中断安全单测（非账号解析场景）：确定性验证场景 7 双连接编排的
 * 测试资源回收在 join 被中断时的<b>可观测末态</b>——中断被消费、caller 最终中断状态为 true、两线程均终止且 {@code survivor=false}；
 * 「统一重算存活性/末尾恰好恢复一次/按剩余预算重试」归因代码审查而非本单测断言（codex round-12 P3-2 据此收窄类摘要，与下方
 * 方法开头注释一致）。该单测以<b>两阶段有界
 * 握手</b>确定性驱动中断路径——worker 用无超时 latch（body 成功路径放行、finally 兜底）、先确认 caller 已阻塞于 join 且
 * worker 存活再中断、后确认中断标志被 join 抛出的 {@code InterruptedException} 消费（助手既不丢失也不吞没中断）才放行
 * worker，杜绝「caller 未进入 join 即被中断+worker 提前终止致助手空转」的偶然通过；两线程均先构造、两个 {@code start()}
 * 移入 try，即便 caller 启动失败 finally 仍放行并回收无超时 worker（codex round-8 P3-2）。该防御性单测只<b>确定性验证</b>
 * 「中断被消费 + 末尾恢复中断 + 最终存活性清净（两线程终止、{@code survivor=false}）」；助手按剩余预算<b>重试</b> join 是其
 * {@code while(isAlive())} 循环的结构性属性、由代码审查确认——本单测不声称确定性证明重试（{@code survivor=false} 无法区分
 * 「重试至 worker 终止」与「放弃后主线程已放行 worker 使其终止」，二者末态一致；codex round-8 P3-1）。其自身清理亦复用该
 * 助手回收两线程，避免裸 join 被中断时提前退出或以新异常掩盖主体原始失败（codex round-5 P3 引入、round-6/7/8/9 P3 逐步消除
 * 其非确定性调度、不可靠的重试观测、自身清理、线程启动保护与命名/注释夸大缺陷）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountResolutionConcurrencyContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private static final String TEST_PEPPER = "t1306-test-pepper-0123456789abcdef-0123456789abcdef";
    private static final String TEST_ARTIFACT_KEY =
            Base64.getEncoder().encodeToString(new byte[]{
                    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                    16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31});

    private static final String APPID = "wx-test-appid";
    private static final String OTHER_APPID = "wx-other-appid";
    /** 单条并发任务收集上限；同时作为线程池有界终止等待上限 */
    private static final long AWAIT_SECONDS = 60L;
    /** pgJDBC socket 读超时（秒）：默认无超时会让阻塞在 JDBC 的 worker 永久挂起，须有界以配合线程池清理 */
    private static final int SOCKET_TIMEOUT_SECONDS = 30;

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private AccessCodeCipher cipher;
    private DataSourceTransactionManager txManager;
    private AccessCodeService accessCodeService;
    private AccessCodeRedemptionService redemptionService;
    private AccountLoginService loginService;
    private UserSessionService sessionService;

    @BeforeAll
    void setUp() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        // 有限的连接/登录/socket 超时：connectTimeout/loginTimeout（各 10s）与 socketTimeout 分别独立配置；socketTimeout=30 只界定
        // 「单次 socket 读」（如阻塞在唯一键锁上的那条查询的读），并非 worker 总生命周期的终止保证——worker 回收仍是「尽力有界」
        // （详见 runConcurrently 与场景 7 finally 说明；codex round-12 P3 据此校正此前「30s 内断开、避免清理阶段无限等待」的过强表述）。
        String baseUrl = PG.getJdbcUrl();
        ds.setUrl((baseUrl.contains("?") ? baseUrl + "&" : baseUrl + "?")
                + "connectTimeout=10&loginTimeout=10&socketTimeout=" + SOCKET_TIMEOUT_SECONDS);
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        this.dataSource = ds;
        this.jdbc = new JdbcTemplate(dataSource);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/platform", "classpath:db/migration/identity")
                .load()
                .migrate();

        this.cipher = AccessCodeCipher.forTesting(TEST_PEPPER, "v1", TEST_ARTIFACT_KEY);
        this.txManager = new DataSourceTransactionManager(dataSource);
        this.accessCodeService = new AccessCodeService(dataSource, txManager, cipher, new JdbcDeliveryPort(dataSource));
        this.sessionService = new UserSessionService(dataSource, txManager);
        this.loginService = new AccountLoginService(dataSource, txManager, new StubWechatIdentityAdapter(), sessionService);
        this.redemptionService = new AccessCodeRedemptionService(dataSource, txManager, cipher, loginService);
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE user_session, design_access_grant, access_code_redemption, "
                + "design_access_code, design_access_code_batch, wechat_identity, account, "
                + "one_time_delivery_ticket");
    }

    private int count(String table, String where) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Integer.class);
        return n == null ? 0 : n;
    }

    private List<String> createInlineBatch(int quantity) {
        return accessCodeService.createBatch(quantity, "INLINE", null, "t1306", "tester").oneTimeCodes();
    }

    /**
     * 并发执行 {@code threads} 路任务：{@link CyclicBarrier} 令全部 worker 就绪后齐发，把「多路同时进入账号解析」的竞争
     * 窗口压到最小；所有 {@link Future} 共享<b>单一总时限</b>（而非逐个重置），收集方不会被逐个拖长。
     *
     * <p>清理有界（codex round-1 P2-4 / round-12 P3-1）：无论成功或异常，都取消未完成任务、关闭线程池并<b>尽力有界等待终止</b>
     * （超时则报告而非无条件保证终止），力求无 worker 仍持连接/写库后下个用例才 {@code TRUNCATE}；这些 worker 均执行 DB 任务，
     * 阻塞时其 socket 读受 {@code socketTimeout=30} 界定（该超时限定单次 socket 读、非 worker 总生命周期），故 {@link #AWAIT_SECONDS}
     * 的终止等待通常足够。任一任务抛异常都经 {@link Future#get} 冒泡使测试失败。
     */
    private <T> List<T> runConcurrently(int threads, IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier startLine = new CyclicBarrier(threads);
        List<Future<T>> futures = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    startLine.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                    return task.apply(idx);
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new TimeoutException("并发任务未在总时限 " + AWAIT_SECONDS + "s 内完成");
                }
                results.add(f.get(remainingNanos, TimeUnit.NANOSECONDS));
            }
            return results;
        } finally {
            for (Future<T> f : futures) {
                f.cancel(true);
            }
            pool.shutdownNow();
            assertThat(pool.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS))
                    .as("并发线程池应在有界时间内终止，否则可能有 worker 阻塞在 JDBC")
                    .isTrue();
        }
    }

    // ========== 场景 1：并发首登不重复建账号 ==========

    @Test
    void concurrentFirstLoginCreatesSingleAccount() throws Exception {
        String sameCode = "login-code-concurrent-first";
        int threads = 16;

        List<LoginResult> results = runConcurrently(threads, i -> loginService.login(APPID, sameCode, null));

        // 全部成功且归属同一账号、同一 openid
        assertThat(results).hasSize(threads);
        assertThat(results.stream().map(LoginResult::accountId).distinct().count())
                .as("同一 (appid, openid) 并发首登只解析出一个账号").isEqualTo(1L);
        assertThat(results.stream().map(LoginResult::openid).distinct().count())
                .as("stub 适配器对同一 code 确定性映射到同一 openid").isEqualTo(1L);
        // 数据库只落一个账号、一条微信身份；每次登录各自签发会话（互不冲突）
        assertThat(count("account", "TRUE")).isEqualTo(1);
        assertThat(count("wechat_identity", "TRUE")).isEqualTo(1);
        assertThat(count("user_session", "TRUE")).isEqualTo(threads);
        // 未兑换 → 全部受限
        assertThat(results).allSatisfy(r -> assertThat(r.restricted()).isTrue());
    }

    // ========== 场景 2：同一微信身份归属一致、appid 隔离（受控身份映射，P3-7）==========

    @Test
    void sameWechatIdentityResolvesSameAccountAndAppidIsolates() {
        // 受控适配器显式编排 (appid, code) -> openid，避免 stub 从 (appid, code) 派生 openid 时
        // 「改 appid 必然改 openid」掩盖 appid 隔离（codex round-1 P3-7）。
        ScriptedWechatIdentityAdapter scripted = new ScriptedWechatIdentityAdapter();
        AccountLoginService scriptedLogin =
                new AccountLoginService(dataSource, txManager, scripted, sessionService);

        // 两个不同的临时 login code 映射到同一微信身份 (APPID, openid-same) → 归属同一账号
        scripted.script(APPID, "code-1", "openid-same", "unionid-same");
        scripted.script(APPID, "code-2", "openid-same", "unionid-same");
        long first = scriptedLogin.login(APPID, "code-1", null).accountId();
        long again = scriptedLogin.login(APPID, "code-2", null).accountId();
        assertThat(again).as("不同登录凭据解析到同一 (appid, openid) 归属同一账号").isEqualTo(first);

        // 相同 openid 但不同 appid → 不同微信身份 → 不同账号（真正验证 appid 参与唯一键，而非被派生掩盖）
        scripted.script(OTHER_APPID, "code-3", "openid-same", "unionid-same");
        long otherAppid = scriptedLogin.login(OTHER_APPID, "code-3", null).accountId();
        assertThat(otherAppid).as("相同 openid 不同 appid 是不同账号").isNotEqualTo(first);

        assertThat(count("account", "TRUE")).isEqualTo(2);
        assertThat(count("wechat_identity", "TRUE")).isEqualTo(2);
        // 归属稳定：按 (appid, openid) 反查各得其所
        assertThat(loginService.findAccountId(APPID, "openid-same")).contains(first);
        assertThat(loginService.findAccountId(OTHER_APPID, "openid-same")).contains(otherAppid);
    }

    // ========== 场景 3：激活流程并发幂等（先登录后兑换，真实入口顺序）==========

    @Test
    void concurrentActivationLoginThenRedeemIsIdempotentSingleGrant() throws Exception {
        String loginCode = "login-code-activate";
        String accessCode = createInlineBatch(1).get(0);
        int threads = 10;

        List<RedemptionResult> results = runConcurrently(threads, i -> {
            LoginResult login = loginService.login(APPID, loginCode, null);
            return redemptionService.redeem(APPID, login.openid(), null, accessCode);
        });

        assertThat(results).hasSize(threads);
        assertThat(results.stream().map(RedemptionResult::accountId).distinct().count())
                .as("并发激活归属同一账号").isEqualTo(1L);
        assertThat(results.stream().map(RedemptionResult::grantId).distinct().count())
                .as("并发兑换同一码只产生一条有效授权").isEqualTo(1L);
        assertThat(count("account", "TRUE")).isEqualTo(1);
        assertThat(count("wechat_identity", "TRUE")).isEqualTo(1);
        assertThat(count("design_access_grant", "status = 'ACTIVE'")).isEqualTo(1);
        assertThat(count("access_code_redemption", "TRUE")).isEqualTo(1);
        assertThat(count("design_access_code", "status = 'CONSUMED'")).isEqualTo(1);
        // 登录各签发会话；兑换不额外签发
        assertThat(count("user_session", "TRUE")).isEqualTo(threads);
    }

    // ========== 场景 4：兑换服务并发解析账号（不经登录，同一新微信直兑不同码）==========

    @Test
    void concurrentRedeemBySameFreshWechatResolvesSingleAccountAndGrant() throws Exception {
        String freshOpenid = "openid-fresh-race";
        List<String> codes = createInlineBatch(8);
        int threads = codes.size();

        // 每路兑换各自不同的有效码，但同属一个从未解析过的新微信身份：账号解析并发全部落在 redeem
        // 外层事务内，任一路失败都意味着账号解析在该路径并发不安全。
        List<RedemptionResult> results = runConcurrently(threads,
                i -> redemptionService.redeem(APPID, freshOpenid, null, codes.get(i)));

        assertThat(results).hasSize(threads);
        assertThat(results.stream().map(RedemptionResult::accountId).distinct().count())
                .as("同一新微信并发直兑只解析出一个账号").isEqualTo(1L);
        assertThat(results.stream().map(RedemptionResult::grantId).distinct().count())
                .as("同一账号并发兑换不同码只产生一条有效授权").isEqualTo(1L);
        assertThat(count("account", "TRUE")).isEqualTo(1);
        assertThat(count("wechat_identity", "TRUE")).isEqualTo(1);
        assertThat(count("design_access_grant", "status = 'ACTIVE'")).isEqualTo(1);
        assertThat(count("access_code_redemption", "TRUE")).isEqualTo(threads);
        assertThat(count("design_access_code", "status = 'CONSUMED'")).isEqualTo(threads);
    }

    // ========== 场景 5（确定性）：被拒兑换的新建账号随外层事务回滚，排除 REQUIRES_NEW ==========

    @Test
    void freshIdentityRedeemingOthersConsumedCodePersistsNoAccount() {
        // 账号 A 登录并兑换码 X：X 变为 CONSUMED 且归属 A
        LoginResult owner = loginService.login(APPID, "code-owner-A", null);
        String consumedCode = createInlineBatch(1).get(0);
        redemptionService.redeem(APPID, owner.openid(), null, consumedCode);
        assertThat(count("account", "TRUE")).isEqualTo(1);

        // 一个全新微信身份（账号不存在）兑换他人已消费的码：redeemInTx 先在 savepoint 内新建账号，随后因码已被他人消费
        // 抛 ACCESS_CODE_ALREADY_CONSUMED，外层兑换事务回滚。NESTED 下新建账号随外层一起回滚、绝不残留；
        // REQUIRES_NEW 会提前独立提交而留下孤儿账号（codex round-1 P2-2）——本用例确定性排除之。
        String freshOpenid = "openid-fresh-rejected";
        assertThatThrownBy(() -> redemptionService.redeem(APPID, freshOpenid, null, consumedCode))
                .isInstanceOf(ServiceException.class);

        assertThat(count("account", "TRUE")).as("被拒兑换的新建账号随外层事务回滚，不落库").isEqualTo(1);
        assertThat(count("wechat_identity", "TRUE")).as("被拒兑换的新建身份随外层事务回滚，不落库").isEqualTo(1);
        assertThat(loginService.findAccountId(APPID, freshOpenid)).as("被拒的全新身份不留账号归属").isEmpty();
    }

    // ========== 场景 6（确定性）：外层事务内冲突后仍存活，排除 REQUIRED ==========

    @Test
    void nestedCollisionInsideOuterTransactionKeepsOuterAlive() {
        // 预置软删除身份：uk_wechat_identity_appid_openid 不含 deleted，而 findAccountId 只查 deleted=FALSE，
        // 故解析查不到、INSERT 必撞唯一键——确定性触发 DuplicateKeyException，不依赖并发时序（codex round-1 P2-3）。
        String tombstoneOpenid = "openid-tombstone";
        jdbc.update("INSERT INTO account (id) VALUES (?)", 999001L);
        jdbc.update("INSERT INTO wechat_identity (id, appid, openid, account_id, deleted) VALUES (?, ?, ?, ?, TRUE)",
                999002L, APPID, tombstoneOpenid, 999001L);

        // 在外层事务内解析该身份（模拟 redeem 的事务上下文）：NESTED 下冲突回滚到 savepoint 并清除中止状态，外层事务仍
        // 健康，catch 内重查与其后语句可正常执行；若改回 REQUIRED，冲突会中止整个共享事务（SQLSTATE 25P02），此时
        // resolveAccount catch 内的重查 findAccountId 先抛 25P02——本用例只捕获 DuplicateKeyException，故其后的 SELECT 1
        // 根本不可达（失败点在重查、不在 SELECT 1；与类 javadoc、交付文档 §4 一致，codex round-3 P3-2）。
        TransactionTemplate outer = new TransactionTemplate(txManager);
        String outcome = outer.execute(status -> {
            try {
                loginService.resolveAccount(APPID, tombstoneOpenid, null);
                return "unexpected-resolve";
            } catch (DuplicateKeyException expected) {
                Integer alive = jdbc.queryForObject("SELECT 1", Integer.class);
                return "outer-alive-after-collision=" + alive;
            }
        });
        assertThat(outcome)
                .as("savepoint 隔离冲突后外层事务存活（REQUIRED 传播下 resolveAccount catch 内重查必先抛 25P02 令本用例变红）")
                .isEqualTo("outer-alive-after-collision=1");

        // savepoint 回滚：resolveAccount 冲突前 INSERT 的新账号行未残留（只余预置的 tombstone）
        assertThat(count("account", "TRUE")).isEqualTo(1);
        assertThat(count("wechat_identity", "TRUE")).isEqualTo(1);
    }

    // ========== 场景 7（确定性）：冲突后重查返回并发赢家账号，且外层事务仍可用 ==========

    @Test
    void loserRecoversToCommittedWinnerAccountInsideOuterTransaction() throws Exception {
        // codex round-2 P3-2：场景 6（tombstone）证明 savepoint 回滚与外层存活，但其重查为空、走重抛分支；场景 1/4 在真实
        // 并发下命中赢家复用路径却非确定性。本用例以双连接编排<b>确定性</b>驱动「冲突 → 重查 → 返回赢家账号」这条恢复主干：
        //   赢家连接 B 先插入并持有 (APPID, contendedOpenid) 唯一键但不提交；输家 A 在外层事务内 resolveAccount：
        //   READ COMMITTED 下 findAccountId 看不到 B 的未提交行（返回空）→ INSERT 撞 B 持有的唯一键锁而阻塞；主线程先记录
        //   A、B 各自的 backend pid，再经 pg_blocking_pids(A) 命中 B 精确确认「A 被 B 阻塞」后放行 B 提交（不用全集群
        //   WHERE NOT granted，避免误判无关等待者，codex round-3 P3-1）；A 的 INSERT 随即抛 DuplicateKeyException →
        //   savepoint 回滚 → catch 重查（新快照见 B 已提交的赢家）→ 返回赢家账号，且 A 的外层事务此后仍能执行 SELECT 1。
        String contendedOpenid = "openid-contended-winner";
        long winnerAccountId = 990001L;
        long winnerIdentityId = 990002L;

        CountDownLatch winnerInserted = new CountDownLatch(1);
        CountDownLatch loserInTx = new CountDownLatch(1);
        CountDownLatch commitWinner = new CountDownLatch(1);
        AtomicReference<Integer> winnerPid = new AtomicReference<>();
        AtomicReference<Integer> loserPid = new AtomicReference<>();
        AtomicReference<Throwable> winnerError = new AtomicReference<>();
        AtomicReference<Long> loserResolved = new AtomicReference<>();
        AtomicReference<String> loserOuterAlive = new AtomicReference<>();
        AtomicReference<Throwable> loserError = new AtomicReference<>();

        Thread winner = new Thread(() -> {
            try (Connection wc = dataSource.getConnection()) {
                wc.setAutoCommit(false);
                // 记录赢家 backend pid：供主线程用 pg_blocking_pids(loserPid) 精确确认输家阻塞在“本赢家”持有的锁上
                try (PreparedStatement ps = wc.prepareStatement("SELECT pg_backend_pid()");
                     ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        winnerPid.set(rs.getInt(1));
                    }
                }
                try (PreparedStatement ps = wc.prepareStatement("INSERT INTO account (id) VALUES (?)")) {
                    ps.setLong(1, winnerAccountId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = wc.prepareStatement(
                        "INSERT INTO wechat_identity (id, appid, openid, account_id) VALUES (?, ?, ?, ?)")) {
                    ps.setLong(1, winnerIdentityId);
                    ps.setString(2, APPID);
                    ps.setString(3, contendedOpenid);
                    ps.setLong(4, winnerAccountId);
                    ps.executeUpdate();
                }
                winnerInserted.countDown();
                if (!commitWinner.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw new TimeoutException("赢家连接未在时限内收到提交信号");
                }
                wc.commit();
            } catch (Throwable t) {
                winnerError.set(t);
            }
        }, "t1306-winner");

        Thread loser = null;
        boolean bodyCompleted = false;
        winner.start();
        try {
            assertThat(winnerInserted.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                    .as("赢家连接应先插入并持有唯一键（未提交）").isTrue();

            loser = new Thread(() -> {
                TransactionTemplate outer = new TransactionTemplate(txManager);
                try {
                    Long resolved = outer.execute(status -> {
                        // jdbc 复用外层事务绑定的连接：先记录输家 backend pid（与随后 resolveAccount 的 INSERT 同连接），
                        // 供主线程精确判定其被赢家阻塞；再进入会阻塞的账号解析
                        loserPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        loserInTx.countDown();
                        long id = loginService.resolveAccount(APPID, contendedOpenid, null);
                        // 恢复成功后外层事务仍须能执行后续语句（同一绑定连接）
                        Integer alive = jdbc.queryForObject("SELECT 1", Integer.class);
                        loserOuterAlive.set("outer-alive=" + alive);
                        return id;
                    });
                    loserResolved.set(resolved);
                } catch (Throwable t) {
                    loserError.set(t);
                }
            }, "t1306-loser");
            loser.start();

            // 确定性等待：输家已进入外层事务（pid 就绪），且其 INSERT 精确阻塞在“赢家 pid”持有的锁上（pg_blocking_pids 建立
            // 输家←赢家的因果阻塞关系），再放行 B 提交——既不以固定 sleep 猜测时序，也不依赖全集群 NOT granted 的宽泛判定
            assertThat(loserInTx.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                    .as("输家应先进入外层事务并记录其 backend pid").isTrue();
            assertThat(awaitLoserBlockedByWinner(winnerPid.get(), loserPid.get()))
                    .as("输家应确定性阻塞在赢家（pg_blocking_pids 命中赢家 pid）持有的唯一键锁上").isTrue();
            commitWinner.countDown();
            bodyCompleted = true;
        } finally {
            // 中断安全回收两 worker：先放行 commitWinner，再由 joinWorkersUntilDeadline 针对同一单调 deadline「尽力」中断安全地回收
            // （被中断的 join 保留剩余预算重试、两 worker 都尝试），全部等待结束后统一重算存活性（杜绝中途快照的陈旧 survivor）并恢复中断。
            // 这是「尽力有界回收至 deadline」而非「无条件保证任何路径都不遗留 worker」——deadline 到期仍可能有 survivor；仅当 bodyCompleted
            // （try 块编排成功、结果断言在本 finally 之后）时才以「仍有 worker 存活」判失败，bodyCompleted=false 时有意跳过该断言、绝不以清理
            // 断言掩盖主体原始异常（codex round-11 P3-1 据实软化此前「任何路径都不遗留 worker」的过强表述；round-12 P3-1 修正 worker 阻塞性质）。
            // 两 worker 阻塞性质不同：winner 阻塞于 commitWinner.await(AWAIT_SECONDS) 定时 latch（放行后即 commit 并终止），唯 loser 阻塞于 JDBC——
            // 其 INSERT 等待 winner 持有的唯一键锁，该 socket 读受 socketTimeout=30 界定（限定单次 socket 读、非 worker 总生命周期）；winner 提交后
            // loser 解除阻塞、完成恢复而终止。
            commitWinner.countDown();
            boolean survivor = joinWorkersUntilDeadline(
                    new Thread[]{loser, winner},
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS));
            if (bodyCompleted && survivor) {
                throw new AssertionError("有界 join 后仍有 worker 存活持连接（清理未达成「无 worker 遗留」）");
            }
        }

        assertThat(loser).isNotNull();
        assertThat(loser.isAlive()).as("输家线程应在时限内完成恢复").isFalse();
        if (winnerError.get() != null) {
            throw new AssertionError("赢家连接异常", winnerError.get());
        }
        if (loserError.get() != null) {
            throw new AssertionError("输家线程异常", loserError.get());
        }
        assertThat(loserResolved.get()).as("冲突恢复后返回并发赢家账号").isEqualTo(winnerAccountId);
        assertThat(loserOuterAlive.get()).as("恢复成功后外层事务仍可用").isEqualTo("outer-alive=1");
        // 输家的 savepoint 子事务已回滚：未新建任何账号/身份，库中只余赢家一行
        assertThat(count("account", "TRUE")).isEqualTo(1);
        assertThat(count("wechat_identity", "TRUE")).isEqualTo(1);
        assertThat(loginService.findAccountId(APPID, contendedOpenid)).contains(winnerAccountId);
    }

    // ========== 清理助手单测（确定性）：join 被中断时中断被消费、恢复中断、无陈旧 survivor ==========

    @Test
    void joinWorkersUntilDeadlineConsumesInterruptionRestoresStatusAndLeavesNoSurvivors() throws Exception {
        // 本防御性单测确定性验证清理助手 joinWorkersUntilDeadline 的可观测末态：（1）中断被消费——阶段 2 观测 caller 中断标志由 true 转 false，
        // 唯一来源即 join 抛出的 InterruptedException 被助手 catch 消费（助手从不调用 Thread.interrupted()，故不丢失也不吞没中断）；（2）caller
        // 最终中断状态为 true；（3）cleanupCaller 与 worker 两线程均终止（isAlive() 皆 false）且 survivor=false。至于「末尾恰好恢复一次中断」
        // 「全部等待结束后统一重算存活性（非循环中途快照）」「按剩余预算重试被中断的 join」三者，均是助手 while(isAlive()) 循环 + 末尾统一处理的
        // 结构性属性、由代码审查确认而非本单测断言——断言只观测上述末态：无法计数恢复次数（多一次恢复仍会通过）、无法证明存活性的重算位置，且
        // survivor=false 无法区分「重试至 worker 终止」与「放弃后主线程已放行 worker 使其终止」（末态一致，codex round-8 P3-1）。故方法名与注释
        // 均不声称「验证重试/恢复次数/重算位置」（codex round-9 P3-1 据此重命名、round-11 P3-2 据实把「恢复一次/统一重算」归因代码审查）。
        // 下列为经 codex round-5~11 逐轮消除非确定性调度与夸大声明的演进记录：
        // codex round-5 P3（历史目标，证明范围后经 round-7/8 收敛，见上）：抽出助手并补本聚焦单测，最初以「join 被中断不提前退出、按剩余预算重试」
        // 为目标；「统一重算存活性（worker 已终止则 survivor=false，无陈旧误报）+ 恢复中断状态」的目标保留，「重试」改由代码审查确认而非测试断言。
        // codex round-6 P3：改为两阶段有界握手消除非确定性——先确认 caller 已阻塞于 join 且 worker 存活再中断。
        // codex round-7 P3：（1）worker 改用无超时 release.await()，杜绝握手期间 worker 因 await 超时提前终止致 caller 的 join
        // 正常返回（那样中断就不会被 join 消费）；（2）阶段 2 据实收敛为只断言「中断标志被消费」——OpenJDK 可能在首个 Object.wait
        // 内部清除中断标志而外部 getState() 仍报 TIMED_WAITING，无法可靠区分「已在助手 catch 中」与「已进入下一次重试 join」；
        // （3）自身清理复用 joinWorkersUntilDeadline 回收两线程，避免裸 join 被中断时提前退出、跳过 worker 回收或以新异常掩盖原始失败。
        // codex round-8 P3-1：round-7 曾声称「重试行为由 survivor=false 反证」，但即便「捕获中断后即放弃」的助手，在主线程据标志
        // 放行 worker、worker 终止后重算存活性同样得 survivor=false——故 survivor=false 无法确定性区分「重试」与「放弃」。本单测
        // 收敛为只确定性验证「中断被消费 + 末尾恢复 + 最终存活性清净」；重试是助手 while(isAlive()) 循环的结构性属性，由代码审查确认。
        // codex round-8 P3-2：两线程先构造、两个 start() 移入 try，即便 caller 启动失败 finally 仍放行并回收无超时 worker，杜绝永久挂起。
        // codex round-9 P3-1：方法名与开头注释此前仍称「验证重试/不提前退出」，与已收敛的 body 矛盾——重命名为恰述可观测末态、重试归因代码审查。
        // codex round-10 P3-2：分离「构造失败（try 前、finally 不执行、两线程未启动故无挂起）」与「启动失败（try 内、finally 必执行）」，软化 finally 保证。
        // codex round-11 P3-1/P3-2：场景 7 与本单测 finally 去「任何路径无遗留 worker」的无条件保证、限定 socketTimeout 仅护数据库场景（本单测无 JDBC）；
        // 开头注释把「恢复恰好一次/统一重算存活性」由「确定性验证」归因为「代码审查确认」，只保留可观测末态为断言。
        // codex round-12 P3-1/P3-2：修正场景 7 finally「两 worker 均阻塞于 JDBC」的事实错误（winner 实为 commitWinner 定时 latch、唯 loser 阻塞 JDBC，
        // socketTimeout 界定单次 socket 读非 worker 生命周期）与 runConcurrently/类 javadoc「确保无 worker」的过强表述；类摘要同步收窄为可观测末态。
        CountDownLatch release = new CountDownLatch(1);
        // 两线程均先构造、两个 start() 移入下方 try（codex round-8 P3-2 / round-9 P3-2）。两类失败分别防护：
        // （a）构造失败（new Thread 抛错，如堆 OOM）发生在 try 之前——finally 不执行，但此时 worker.start() 亦未执行、两线程都未启动，故无挂起；
        // （b）启动失败（start() 抛错，如 native 线程耗尽 OOM）发生在 try 之内——finally 必执行：放行 latch 并有界回收，已启动的无超时 worker 不会
        // 永久挂起（未启动的线程 isAlive() 为 false，助手 join 立即返回）。
        Thread worker = new Thread(() -> {
            try {
                // 无超时等待：在 body 成功路径（release.countDown()）或 finally 兜底放行前保持阻塞，确保握手期间 worker 稳定存活、
                // caller 的 join 不会正常返回
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "t1306-cleanup-worker");
        AtomicReference<Boolean> survivorResult = new AtomicReference<>();
        AtomicReference<Boolean> finalInterruptStatus = new AtomicReference<>();
        Thread cleanupCaller = new Thread(() -> {
            boolean survivor = joinWorkersUntilDeadline(
                    new Thread[]{worker}, System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS));
            survivorResult.set(survivor);
            finalInterruptStatus.set(Thread.currentThread().isInterrupted());
        }, "t1306-cleanup-caller");

        try {
            worker.start();
            cleanupCaller.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
            // 阶段 1：轮询确认 caller 已阻塞在 worker.join（TIMED_WAITING 且 worker 存活）——caller 唯一的时间等待即 join，故为可靠代理
            boolean callerBlockedInJoin = false;
            while (System.nanoTime() < deadline) {
                if (cleanupCaller.getState() == Thread.State.TIMED_WAITING && worker.isAlive()) {
                    callerBlockedInJoin = true;
                    break;
                }
                Thread.sleep(1L);
            }
            assertThat(callerBlockedInJoin).as("caller 应先阻塞在 worker.join（worker 未放行）").isTrue();

            // worker 用无超时 await 且未放行，故 caller 的 join 不可能正常返回：此刻中断必致 join 抛 InterruptedException
            cleanupCaller.interrupt();

            // 阶段 2：轮询确认中断标志已被消费——助手从不调用 Thread.interrupted()（那也会清标志），标志转 false 的唯一来源即
            // join 抛 InterruptedException 并被助手 catch 消费。故标志转 false 确定性证明「中断已被 join 消费、助手未丢失/未吞没中断」。
            // 不附加 getState()==TIMED_WAITING 判定「已进入重试 join」：标志可能在首个 wait 内部即被清除而状态尚未转出，无法可靠区分。
            boolean interruptConsumed = false;
            while (System.nanoTime() < deadline) {
                if (!cleanupCaller.isInterrupted()) {
                    interruptConsumed = true;
                    break;
                }
                Thread.sleep(1L);
            }
            assertThat(interruptConsumed)
                    .as("中断应被 join 抛出的 InterruptedException 消费（助手既不丢失也不吞没中断）").isTrue();

            // 握手确认后在 body 成功路径放行 worker：worker 终止后助手的 join（首次或被中断后重试的）返回，统一重算存活性得 survivor=false
            release.countDown();
        } finally {
            // 泄漏防护 + 不掩盖原始失败：无论主体断言成败、亦无论 cleanupCaller 是否成功启动，都先放行 latch、再复用 joinWorkersUntilDeadline
            // 以单一新 deadline 中断安全地「尝试」回收两线程（助手内部对每个 worker 按剩余预算重试 join、不因单次中断提前退出而转向下一个，且不抛
            // 新异常——InterruptedException 被内部消费并在末尾恢复中断，故即便主体断言已抛出也不以新异常掩盖原始失败）。注意：此处不检查助手返回的
            // survivor——回收是「尽力有界 join 至 deadline」而非「无条件保证终止」（主体断言失败时其后的存活性断言亦被跳过）。本聚焦单测是纯
            // latch/join 编排、不使用任何 JDBC 连接，worker 的唯一挂起来源即无超时 release.await() 未被放行，故本 finally 的 release.countDown()
            // 才是杜绝其永久挂起的关键（数据库场景 1~7 的 worker 才阻塞于 JDBC、另有 socketTimeout=30 兜底，但那不适用于本无连接单测，且 socket 读
            // 超时本身也非通用的线程终止保证）（codex round-7 P3-2 / round-8 P3-2 / round-9 P3-2 / round-11 P3-1 据实限定 socketTimeout 适用范围）
            release.countDown();
            joinWorkersUntilDeadline(
                    new Thread[]{cleanupCaller, worker}, System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS));
        }

        assertThat(cleanupCaller.isAlive()).as("清理线程应在时限内完成").isFalse();
        assertThat(worker.isAlive()).as("worker 应在放行后终止").isFalse();
        // 最终存活性清净：worker 已终止、助手统一重算后 survivor=false（无陈旧误报）。注：survivor=false 不能确定性区分「助手按
        // 剩余预算重试 join 至 worker 终止」与「助手捕获中断后即放弃、但主线程已放行 worker 使其终止」——两种情形末态一致（codex
        // round-8 P3-1）。重试是助手 while(isAlive()) 循环的结构性属性、由代码审查确认；本防御性单测只确定性验证「中断被消费 +
        // 末尾恢复 + 最终存活性清净」，不声称确定性证明重试。
        assertThat(survivorResult.get()).as("worker 已终止，重算后不应有存活者").isFalse();
        // 助手在全部等待结束后恢复中断：caller 最终中断状态为 true（该 true 唯一来源是助手末尾恢复，因标志曾被 InterruptedException 清除）
        assertThat(finalInterruptStatus.get()).as("清理结束后应恢复中断状态").isTrue();
    }

    /**
     * 轮询直到<b>赢家 pid</b> 出现在 <b>输家 pid</b> 的 {@code pg_blocking_pids} 中或超时。用于<b>确定性</b>判定输家的
     * INSERT 已精确阻塞在“赢家持有的唯一键锁”上：{@code pg_blocking_pids(loserPid)} 直接建立“输家被赢家阻塞”的因果关系，
     * 不像全集群 {@code pg_locks WHERE NOT granted} 可能命中无关等待者（codex round-3 P3-1）。只有确认此因果后放行赢家提交，
     * 才能保证输家走「唯一键冲突 → catch 重查 → 复用赢家」的恢复路径，而非因赢家提前提交而侥幸走 findAccountId 快路径。
     */
    private boolean awaitLoserBlockedByWinner(int winnerPid, int loserPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            Boolean blocked = jdbc.queryForObject(
                    "SELECT ?::int = ANY(pg_blocking_pids(?::int))", Boolean.class, winnerPid, loserPid);
            if (Boolean.TRUE.equals(blocked)) {
                return true;
            }
            Thread.sleep(50L);
        }
        return false;
    }

    /**
     * 有界且中断安全地回收 worker 线程：所有 {@code join} 针对<b>同一单调 deadline</b> 重试——某个 join 被中断只记录中断、
     * 按<b>剩余预算</b>继续重试该 worker 与其余 worker（不因单次中断提前退出而遗漏任何一个）；<b>全部等待结束后</b>再统一以
     * {@code isAlive()} 重算存活性（反映最终状态，杜绝循环中途快照导致的陈旧 survivor）；最后恢复一次中断状态。
     * 返回是否仍有 worker 存活。连接的最终兜底由 JDBC {@code socketTimeout=30} 提供（codex round-5 P3）。
     */
    private static boolean joinWorkersUntilDeadline(Thread[] workers, long deadlineNanos) {
        boolean interrupted = false;
        for (Thread worker : workers) {
            if (worker == null) {
                continue;
            }
            // 按剩余预算重试：join 被中断（会清除中断标志）后不提前退出，只要 worker 仍存活且未到 deadline 就继续 join
            while (worker.isAlive()) {
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                if (remainingMillis <= 0L) {
                    break;
                }
                try {
                    worker.join(remainingMillis);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        // 全部等待结束后统一重算存活性（而非循环中途的瞬时快照）
        for (Thread worker : workers) {
            if (worker != null && worker.isAlive()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 受控身份适配器：按显式编排的 (appid, code) -> (openid, unionid) 返回会话，未编排的组合直接失败。
     * 用于精确验证身份归属契约——不同登录凭据可映射到同一微信身份、相同 openid 在不同 appid 下是不同身份，
     * 避免 {@link StubWechatIdentityAdapter} 从 (appid, code) 派生 openid 时「改 appid 必然改 openid」掩盖 appid 隔离。
     */
    private static final class ScriptedWechatIdentityAdapter implements WechatIdentityPort {

        private final Map<String, WechatSession> scripted = new HashMap<>();

        void script(String appid, String code, String openid, String unionid) {
            scripted.put(appid + "\u0000" + code, new WechatSession(openid, unionid));
        }

        @Override
        public WechatSession codeToSession(String appid, String loginCode) {
            WechatSession session = scripted.get(appid + "\u0000" + loginCode);
            if (session == null) {
                throw new IllegalStateException("未编排的微信身份: appid=" + appid + " code=" + loginCode);
            }
            return session;
        }
    }
}
