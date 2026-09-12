package cn.iocoder.yudao.server.wiring;

import com.baomidou.dynamic.datasource.processor.DsProcessor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The application uses deployment-defined datasource names; no request/header/session/SpEL routing. */
@Configuration(proxyBeanMethods=false)
public class StaticDatasourceRoutingConfiguration {
    @Bean public DsProcessor dsProcessor() {
        return new DsProcessor() {
            @Override public boolean matches(String key) { return true; }
            @Override public String doDetermineDatasource(MethodInvocation invocation,String key) {
                if(key==null || !key.matches("[A-Za-z0-9_-]{1,64}"))
                    throw new IllegalArgumentException("DYNAMIC_DATASOURCE_EXPRESSION_DISABLED");
                return key;
            }
        };
    }
}
