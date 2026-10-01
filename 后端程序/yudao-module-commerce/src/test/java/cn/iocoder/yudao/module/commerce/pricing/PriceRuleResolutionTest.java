package cn.iocoder.yudao.module.commerce.pricing;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PriceRuleResolutionTest {

    @Test
    void springSelectsProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> mock(DataSource.class));
            context.register(PriceRuleService.class);
            context.refresh();

            assertThat(context.getBean(PriceRuleService.class)).isNotNull();
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void resolutionIsPartOfLookupAndMissing4kNeverFallsBackTo2k() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant now = Instant.parse("2026-09-29T12:00:00Z");
        PriceRuleService.PriceRule twoK = new PriceRuleService.PriceRule(
                21, 1, "FLAT", "2K", 10, 1, 4, now.minusSeconds(60), null, "ACTIVE");
        when(jdbc.query(anyString(), any(RowMapper.class), eq("FLAT"), eq("2K"),
                any(Timestamp.class), any(Timestamp.class))).thenReturn(List.of(twoK));
        when(jdbc.query(anyString(), any(RowMapper.class), eq("FLAT"), eq("4K"),
                any(Timestamp.class), any(Timestamp.class))).thenReturn(List.of());
        PriceRuleService service = new PriceRuleService(jdbc);

        assertThat(service.quote("FLAT", "2K", 2, now).getRuleId()).isEqualTo(21);
        assertThatThrownBy(() -> service.quote("FLAT", "4K", 2, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("4K");
    }

    @Test
    void confirmationFromAnotherResolutionRuleIsRejected() {
        PricingPort.PriceSnapshot fourK = new PricingPort.PriceSnapshot(
                44, 1, "FLAT", "4K", 20, 1, 20);

        assertThatThrownBy(() -> PricingPort.requireConfirmed(
                fourK, new PricingPort.PriceConfirmation("22", 1)))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(1_072_000_001));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void frozen4kQuoteIsRejectedWhenRuleBelongsTo2k() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant now = Instant.parse("2026-09-29T12:00:00Z");
        PriceRuleService.PriceRule twoK = new PriceRuleService.PriceRule(
                44, 1, "FLAT", "2K", 20, 1, 4, now.minusSeconds(60), null, "ACTIVE");
        when(jdbc.query(anyString(), any(RowMapper.class), eq("FLAT"), eq("4K"),
                any(Timestamp.class), any(Timestamp.class))).thenReturn(List.of(twoK));
        PriceRuleService service = new PriceRuleService(jdbc);
        PriceRuleQuote frozenFourK = new PriceRuleQuote(
                44, 1, "FLAT", "4K", 20, 1, 20, now.minusSeconds(60));

        assertThatThrownBy(() -> service.validateSnapshotStillValid(frozenFourK, now))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(1_072_000_001));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void newlyEffectiveRuleInvalidatesOlderConfirmationInSameResolution() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant now = Instant.parse("2026-09-29T12:00:00Z");
        PriceRuleService.PriceRule current = new PriceRuleService.PriceRule(
                45, 1, "FLAT", "4K", 30, 1, 4, now.minusSeconds(10), null, "ACTIVE");
        when(jdbc.query(anyString(), any(RowMapper.class), eq("FLAT"), eq("4K"),
                any(Timestamp.class), any(Timestamp.class))).thenReturn(List.of(current));
        PriceRuleService service = new PriceRuleService(jdbc);
        PriceRuleQuote olderConfirmation = new PriceRuleQuote(
                44, 1, "FLAT", "4K", 20, 1, 20, now.minusSeconds(60));

        assertThatThrownBy(() -> service.validateSnapshotStillValid(olderConfirmation, now))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(1_072_000_001));
    }
}
