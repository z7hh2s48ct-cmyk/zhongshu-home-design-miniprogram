package cn.iocoder.yudao.framework.banner.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 项目启动状态。原始框架来源：芋道源码（ruoyi-vue-pro）。
 */
@Slf4j
public class BannerApplicationRunner implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        log.info("众墅之家设计服务启动完成；部署与接口说明见 docs/zhongshu-design/README.md");
    }
}
