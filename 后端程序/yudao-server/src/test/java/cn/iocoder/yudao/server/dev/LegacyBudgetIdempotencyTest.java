package cn.iocoder.yudao.server.dev;

import cn.iocoder.yudao.module.design.budget.BudgetService;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.commerce.points.*;
import cn.iocoder.yudao.module.commerce.pricing.UsagePointPriceService;
import cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class LegacyBudgetIdempotencyTest {
    @Test void concurrentRetryChargesOnceRejectsChangedIntentAndIsolatesAccounts() throws Exception {
        try(var pg=new PostgreSQLContainer<>("postgres:17-alpine")) {
            pg.start();var ds=new SimpleDriverDataSource();ds.setDriverClass(org.postgresql.Driver.class);
            ds.setUrl(pg.getJdbcUrl());ds.setUsername(pg.getUsername());ds.setPassword(pg.getPassword());
            Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform","classpath:db/migration/design","classpath:db/migration/commerce").load().migrate();
            var jdbc=new JdbcTemplate(ds);var tx=new DataSourceTransactionManager(ds);
            var projects=new DesignProjectService(ds,tx,null,null,null,null);
            var points=new PointAccountService(ds,tx);var usage=new UsagePointPriceService(ds,tx,new PointLedgerPortAdapter(points));
            usage.createRule("BUDGET_ESTIMATE",7,Instant.now().minusSeconds(1),null,"fixture");
            var quote=usage.quote("BUDGET_ESTIMATE");var confirmed=new UsagePricingPort.Confirmation(quote.product(),String.valueOf(quote.ruleId()),quote.ruleVersion());
            var budgets=new BudgetService(ds,projects,tx,usage);budgets.createRuleVersion("VAR1","BRICK","A",100,200,Instant.now().minusSeconds(1));
            long project=projects.createProject(7,"SELF_UPLOAD",null,null,Map.of());
            long other=projects.createProject(8,"SELF_UPLOAD",null,null,Map.of());
            points.credit(7,"RECHARGE_BASE_CREDIT",100,"fixture","7","fixture-credit7",null,"fixture");
            points.credit(8,"RECHARGE_BASE_CREDIT",100,"fixture","8","fixture-credit8",null,"fixture");
            var pool=Executors.newFixedThreadPool(4);Set<Long> ids=new HashSet<>();
            try {
                var calls=new ArrayList<Callable<Long>>();for(int i=0;i<8;i++)calls.add(()->budgets.createEstimate(7,project,"VAR1","BRICK","A",120,null,confirmed,"same-key").estimateId());
                for(var future:pool.invokeAll(calls))ids.add(future.get(20,TimeUnit.SECONDS));
            } finally {pool.shutdownNow();}
            assertThat(ids).hasSize(1);assertThat(points.findAccount(7).orElseThrow().availablePoints()).isEqualTo(93);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_estimate",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM service_usage_charge",Integer.class)).isEqualTo(1);
            assertThatThrownBy(()->budgets.createEstimate(7,project,"VAR1","BRICK","A",121,null,confirmed,"same-key"))
                    .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,e->assertThat(e.getCode()).isEqualTo(409));
            assertThatThrownBy(()->budgets.createEstimate(7,other,"VAR1","BRICK","A",120,null,confirmed,"same-key"))
                    .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
            var second=budgets.createEstimate(8,other,"VAR1","BRICK","A",120,null,confirmed,"same-key");
            assertThat(ids).doesNotContain(second.estimateId());assertThat(points.findAccount(8).orElseThrow().availablePoints()).isEqualTo(93);
            usage.retireRule(quote.ruleId(),"fixture");
            assertThat(budgets.createEstimate(7,project,"VAR1","BRICK","A",120,null,confirmed,"same-key").estimateId()).isIn(ids);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_estimate",Integer.class)).isEqualTo(2);
            assertThatThrownBy(()->budgets.createEstimate(7,project,"VAR1","BRICK","A",120,null,confirmed))
                    .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,e->assertThat(e.getCode()).isEqualTo(400));
        }
    }
}
