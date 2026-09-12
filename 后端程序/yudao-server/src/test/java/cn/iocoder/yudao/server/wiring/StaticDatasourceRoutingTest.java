package cn.iocoder.yudao.server.wiring;

import com.baomidou.dynamic.datasource.processor.DsProcessor;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAopConfiguration;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class StaticDatasourceRoutingTest {
    @Test void actualLibraryAutoConfigurationUsesOnlyTheSafeProcessor() {
        new ApplicationContextRunner()
                .withUserConfiguration(StaticDatasourceRoutingConfiguration.class)
                .withConfiguration(AutoConfigurations.of(DynamicDataSourceAopConfiguration.class)).run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(DsProcessor.class);
                    var processor=context.getBean(DsProcessor.class);
                    assertThat(processor.determineDatasource(null,"master")).isEqualTo("master");
                    for(String key:java.util.List.of("#header.ds","#session.ds","#request.ds","T(java.lang.System)","#{7*7}"))
                        assertThatThrownBy(()->processor.determineDatasource(null,key)).hasMessage("DYNAMIC_DATASOURCE_EXPRESSION_DISABLED");
                });
    }
}
