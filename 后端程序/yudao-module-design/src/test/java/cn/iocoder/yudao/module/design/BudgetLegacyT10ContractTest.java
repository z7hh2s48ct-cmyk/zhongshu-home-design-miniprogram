package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.budget.BudgetService;
import cn.iocoder.yudao.module.design.controller.app.AppBudgetController;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.BUDGET_INPUT_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** No database substitute: only pre-query guards and the legacy response mapping are tested here. */
class BudgetLegacyT10ContractTest {

    @Test
    void creationReusesProjectOwnershipBeforeAnyBudgetDatabaseAccess() {
        var dataSource = mock(DataSource.class);
        var projects = mock(DesignProjectService.class);
        when(projects.getProject(100L, 2L)).thenReturn(Optional.empty());
        var budgets = new BudgetService(dataSource, projects);

        assertThatThrownBy(() -> budgets.createEstimate(2, 100, "VAR1", "BRICK", "A", 120, null))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(RESOURCE_FORBIDDEN.getCode()));
        verify(projects).getProject(100L, 2L);
        verifyNoInteractions(dataSource);
    }

    @Test
    void projectHistoryAlsoChecksOwnershipBeforeQueryingBudgets() {
        var dataSource = mock(DataSource.class);
        var projects = mock(DesignProjectService.class);
        when(projects.getProject(100L, 2L)).thenReturn(Optional.empty());
        var budgets = new BudgetService(dataSource, projects);

        assertThatThrownBy(() -> budgets.listByProject(2, 100))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(RESOURCE_FORBIDDEN.getCode()));
        verifyNoInteractions(dataSource);
    }

    @Test
    void legacyAreaLimitIsEnforcedForNonHttpCallersToo() {
        var dataSource = mock(DataSource.class);
        var projects = mock(DesignProjectService.class);
        when(projects.getProject(100L, 1L)).thenReturn(Optional.of(
                new DesignProjectService.ProjectSnapshot(100, 1, "SELF_UPLOAD", null, "FLAT", "ACTIVE")));
        var budgets = new BudgetService(dataSource, projects);

        for (int area : new int[]{-1, 0, 10001, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> budgets.createEstimate(1, 100, "VAR1", "BRICK", "A", area, null))
                    .isInstanceOfSatisfying(ServiceException.class,
                            e -> assertThat(e.getCode()).isEqualTo(BUDGET_INPUT_INVALID.getCode()));
        }
        verifyNoInteractions(dataSource);
    }

    @Test
    void legacyResponsePreservesExactIdsAndRangeWithoutInventingItemization() {
        var controller = new AppBudgetController();
        var estimate = new BudgetService.Estimate(9007199254740993L, 9007199254740995L, "123",
                3600000L, 6000000L, Map.of("buildingArea", 120), "仅供参考", Instant.parse("2026-09-08T00:00:00Z"));
        cn.iocoder.yudao.module.design.controller.app.vo.AppBudgetEstimateRespVO response =
                ReflectionTestUtils.invokeMethod(controller, "toVo", estimate);

        assertThat(response).isNotNull();
        assertThat(response.getEstimateId()).isEqualTo("9007199254740993");
        assertThat(response.getProjectId()).isEqualTo("9007199254740995");
        assertThat(response.getModel()).isEqualTo("LEGACY_RANGE");
        assertThat(response.getTotalMinCents()).isEqualTo(3600000L);
        assertThat(response.getTotalMaxCents()).isEqualTo(6000000L);
        assertThat(response.getInputSnapshot()).containsExactlyEntriesOf(Map.of("buildingArea", 120));
        assertThat(response.getBreakdown()).isNull();
    }
}
