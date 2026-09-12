package cn.iocoder.yudao.module.design.asset;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 内容安全审核端口（架构 §6.9：内容审核与版权授权是两个独立状态）
 *
 * 直通 Stub（自动 PASSED）；真实内容安全 Adapter（如云检测）就绪后替换，
 * 未通过 moderation 的资产不能下载、发布或进入 Provider。
 *
 * 装配条件（T13-01）：zhongshu.design.asset.moderation.provider=stub。内容审核真实接入为后续批次（B3 后置），
 * 本期保留端口与门禁；生产禁止 stub，由 ZhongshuWiringEnvironmentPostProcessor 启动守卫强制。
 */
@Component
@ConditionalOnProperty(prefix = "zhongshu.design.asset.moderation", name = "provider", havingValue = "stub")
public class StubContentModerationAdapter implements ContentModerationPort {

    @Override
    public boolean pass(String assetType, byte[] content) {
        // P0 直通；内容审核决策与版权授权互不影响
        return true;
    }

}
