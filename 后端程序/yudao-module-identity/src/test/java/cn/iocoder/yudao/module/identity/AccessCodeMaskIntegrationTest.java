package cn.iocoder.yudao.module.identity;

import cn.iocoder.yudao.module.identity.accesscode.AccessCodeService;
import cn.iocoder.yudao.module.identity.controller.admin.AccessCodeAdminController;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessCodeMaskIntegrationTest {
    PostgreSQLContainer<?> container;JdbcTemplate jdbc;AccessCodeService service;AccessCodeAdminController controller;
    @BeforeAll void database() {
        var ds=new SimpleDriverDataSource();ds.setDriverClass(org.postgresql.Driver.class);
        if(Boolean.getBoolean("prelaunch.nativePg")) {
            ds.setUrl("jdbc:postgresql://127.0.0.1:55445/postgres");ds.setUsername("dev_fixture");var admin=new JdbcTemplate(ds);
            if(admin.queryForObject("SELECT count(*) FROM pg_database WHERE datname='prelaunch_codes'",Integer.class)==0)admin.execute("CREATE DATABASE prelaunch_codes");
            ds.setUrl("jdbc:postgresql://127.0.0.1:55445/prelaunch_codes");
        }else{container=new PostgreSQLContainer<>("postgres:17-alpine");container.start();ds.setUrl(container.getJdbcUrl());ds.setUsername(container.getUsername());ds.setPassword(container.getPassword());}
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/identity").load().migrate();jdbc=new JdbcTemplate(ds);
        service=new AccessCodeService(ds,new DataSourceTransactionManager(ds),null,null);controller=new AccessCodeAdminController();
        ReflectionTestUtils.setField(controller,"accessCodeService",service);
    }
    @AfterAll void stop(){if(container!=null)container.stop();}
    @BeforeEach void fixtures(){
        jdbc.execute("TRUNCATE access_code_redemption,design_access_code,design_access_code_batch");
        jdbc.update("INSERT INTO design_access_code_batch(id,quantity,delivery_mode,issued_by,deleted) VALUES(1,4,'INLINE','fixture',FALSE),(2,1,'INLINE','fixture',TRUE)");
        code(11,1,"ZSZJ-****-AB12","ACTIVE");code(12,1,"ZSZJ-****-ab34","ACTIVE");code(13,1,"ZSZJ-****-CD12","DISABLED");
        code(14,1,"ZSZJ-****-A%_B","ACTIVE");code(21,2,"ZSZJ-****-AB99","ACTIVE");
    }
    void code(long id,long batch,String mask,String status){jdbc.update("INSERT INTO design_access_code(id,batch_id,code_hash,pepper_version,code_mask,status) VALUES(?,?,?,'fixture',?,?)",id,batch,String.format("%064d",id),mask,status);}
    @Test void normalizedMaskFiltersCountAndBothPagesThroughController() {
        var first=controller.getAccessCodePage(1L,"ACTIVE","  aB  ",1,1).getData();
        var second=controller.getAccessCodePage(1L,"ACTIVE","aB",2,1).getData();
        assertThat(first.getTotal()).isEqualTo(2);assertThat(first.getList()).extracting("id").containsExactly("11");
        assertThat(second.getTotal()).isEqualTo(2);assertThat(second.getList()).extracting("id").containsExactly("12");
    }
    @Test void wildcardAndSqlTextAreLiteralAndDeletedBatchesAreExcludedFromCount() {
        assertThat(service.countCodes(null,null,"%_")).isEqualTo(1);
        assertThat(service.pageCodes(null,null,"%_",1,20)).extracting("id").containsExactly(14L);
        assertThat(service.countCodes(null,null,"' OR 1=1 --")).isZero();
        assertThat(service.countCodes(null,null," ")).isEqualTo(4);
        assertThat(service.pageCodes(null,null,null,1,20)).hasSize(4);
        assertThatThrownBy(()->service.countCodes(null,null,"A".repeat(65))).isInstanceOf(IllegalArgumentException.class);
    }
}
