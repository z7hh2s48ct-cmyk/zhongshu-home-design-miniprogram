package cn.iocoder.yudao;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1A 平台错误码注册表合同（架构 §7.6）
 *
 * 锁定：稳定字符串标识（常量字段名）↔ 数值码一一对应、全局唯一、msg 非空、各模块段位正确。
 * 新增错误码必须先改本测试的期望集合，防止未评审的同义码混入。
 */
class ZhongshuErrorCodeRegistryTest {

    /** 架构 §7.6 及已批准 T10 预算合同的稳定错误码字符串标识。 */
    private static final Set<String> STABLE_NAMES = Set.of(
            // identity 1-070
            "ACCESS_CODE_INVALID", "ACCESS_CODE_EXPIRED", "ACCESS_CODE_DISABLED",
            "ACCESS_CODE_ALREADY_CONSUMED", "ACCESS_CODE_SECRET_ALREADY_EXPOSED", "WECHAT_IDENTITY_ALREADY_BOUND",
            // design 1-071
            "DESIGN_STAGE_CONFLICT", "ASSET_VALIDATION_FAILED", "PUBLICATION_VALIDATION_FAILED",
            "GENERATION_REFERENCE_NOT_AUTHORIZED", "SUBMISSION_STATE_CONFLICT",
            "BUDGET_INPUT_INVALID", "BUDGET_INCOMPLETE", "BUDGET_PRICE_CONFLICT", "BUDGET_AMOUNT_LIMIT",
            // ai-orchestration 1-073
            "AI_JOB_NOT_CANCELLABLE",
            // commerce 1-072
            "POINTS_INSUFFICIENT", "PRICE_RULE_CHANGED", "PAYMENT_ORDER_STATE_CONFLICT",
            "PAYMENT_PAID_CREDIT_PENDING", "REFUND_POINTS_ALREADY_USED", "REFUND_POLICY_NOT_ENABLED",
            // 平台合同级 1-099
            "IDEMPOTENCY_KEY_REUSED", "STATE_VERSION_CONFLICT", "RESOURCE_FORBIDDEN");

    private record Entry(String name, int code, String msg, Class<?> owner) {
    }

    private List<Entry> collectAll() {
        List<Class<?>> holders = List.of(
                cn.iocoder.yudao.module.identity.enums.ErrorCodeConstants.class,
                cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.class,
                cn.iocoder.yudao.module.commerce.enums.ErrorCodeConstants.class,
                cn.iocoder.yudao.module.aiorchestration.enums.ErrorCodeConstants.class,
                ZhongshuErrorCodeConstants.class);
        List<Entry> entries = new ArrayList<>();
        for (Class<?> holder : holders) {
            for (Field field : holder.getDeclaredFields()) {
                if (field.getType() == ErrorCode.class) {
                    try {
                        ErrorCode ec = (ErrorCode) field.get(null);
                        entries.add(new Entry(field.getName(), ec.getCode(), ec.getMsg(), holder));
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        return entries;
    }

    @Test
    void registryMatchesStableNamesExactly() {
        List<Entry> entries = collectAll();
        Set<String> names = new HashSet<>(entries.stream().map(Entry::name).toList());
        assertThat(names).as("注册表与 §7.6 完全一致，不允许同义码").isEqualTo(STABLE_NAMES);
        assertThat(entries).hasSize(STABLE_NAMES.size());
    }

    @Test
    void numericCodesAreGloballyUnique() {
        List<Entry> entries = collectAll();
        List<Integer> codes = entries.stream().map(Entry::code).toList();
        assertThat(codes).doesNotHaveDuplicates();
    }

    @Test
    void everyMessageIsNotBlank() {
        assertThat(collectAll()).allSatisfy(e -> assertThat(e.msg()).isNotBlank());
    }

    @Test
    void moduleSegmentsAreReservedCorrectly() {
        for (Entry e : collectAll()) {
            String owner = e.owner().getName();
            if (owner.contains(".identity.")) {
                assertThat(e.code()).as("%s 段位", e.name()).isBetween(1_070_000_000, 1_070_000_999);
            } else if (owner.contains(".design.")) {
                assertThat(e.code()).as("%s 段位", e.name()).isBetween(1_071_000_000, 1_071_000_999);
            } else if (owner.contains(".commerce.")) {
                assertThat(e.code()).as("%s 段位", e.name()).isBetween(1_072_000_000, 1_072_000_999);
            } else if (owner.contains(".aiorchestration.")) {
                assertThat(e.code()).as("%s 段位", e.name()).isBetween(1_073_000_000, 1_073_000_999);
            } else {
                assertThat(e.code()).as("%s 段位", e.name()).isBetween(1_099_000_000, 1_099_000_999);
            }
        }
    }

}
