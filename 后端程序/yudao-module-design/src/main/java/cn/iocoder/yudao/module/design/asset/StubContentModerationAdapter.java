package cn.iocoder.yudao.module.design.asset;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

/**
 * 内容安全审核端口（架构 §6.9：内容审核与版权授权是两个独立状态）
 *
 * P0 提供直通 Stub（自动 PASSED）；真实内容安全 Adapter（如云检测）就绪后替换，
 * 未通过 moderation 的资产不能下载、发布或进入 Provider。
 */
@Component
@Profile("zsdev & !prod & !production")
public class StubContentModerationAdapter implements ContentModerationPort {

    @Override
    public boolean pass(String assetType, byte[] content) {
        // P0 直通；内容审核决策与版权授权互不影响
        return true;
    }

}
