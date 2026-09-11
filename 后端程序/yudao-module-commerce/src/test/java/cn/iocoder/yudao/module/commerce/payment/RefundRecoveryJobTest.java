package cn.iocoder.yudao.module.commerce.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RefundRecoveryJob} 隔离测试（T13-29）。
 *
 * <p>验证调度器的三条契约：① 开关关闭时不触碰任何资金动作；② 开启时重启补偿走
 * {@code recoverPendingRefunds}、定时轮走 {@code reconcileRefunds}；③ 领域方法抛异常时被吞并
 * （仅告警），绝不让后台调度线程因单次失败而中断。
 */
@ExtendWith(MockitoExtension.class)
class RefundRecoveryJobTest {

    @Mock
    private RechargePaymentService payment;

    private RefundRecoveryJob job;

    @BeforeEach
    void setUp() {
        job = new RefundRecoveryJob(payment);
    }

    private void enable(boolean on) {
        ReflectionTestUtils.setField(job, "enabled", on);
    }

    // ========== 重启补偿（ApplicationReadyEvent） ==========

    @Test
    @DisplayName("开启时 onStartup 调用 recoverPendingRefunds")
    void startupRunsRecoverWhenEnabled() {
        enable(true);
        when(payment.recoverPendingRefunds()).thenReturn(2);

        job.onStartup();

        verify(payment).recoverPendingRefunds();
    }

    @Test
    @DisplayName("关闭时 onStartup 不触碰资金动作")
    void startupSkippedWhenDisabled() {
        enable(false);

        job.onStartup();

        verify(payment, never()).recoverPendingRefunds();
        verify(payment, never()).reconcileRefunds();
    }

    @Test
    @DisplayName("onStartup 吞并领域异常，不向上传播")
    void startupSwallowsException() {
        enable(true);
        when(payment.recoverPendingRefunds()).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> job.onStartup()).doesNotThrowAnyException();
    }

    // ========== 定时收口（@Scheduled） ==========

    @Test
    @DisplayName("开启时 tick 调用 reconcileRefunds")
    void tickRunsReconcileWhenEnabled() {
        enable(true);
        when(payment.reconcileRefunds()).thenReturn(1);

        job.tick();

        verify(payment).reconcileRefunds();
    }

    @Test
    @DisplayName("关闭时 tick 不触碰资金动作")
    void tickSkippedWhenDisabled() {
        enable(false);

        job.tick();

        verify(payment, never()).reconcileRefunds();
        verify(payment, never()).recoverPendingRefunds();
    }

    @Test
    @DisplayName("tick 吞并领域异常，不中断调度线程")
    void tickSwallowsException() {
        enable(true);
        when(payment.reconcileRefunds()).thenThrow(new RuntimeException("channel timeout"));

        assertThatCode(() -> job.tick()).doesNotThrowAnyException();
    }
}
