package cn.iocoder.yudao.module.design.asset;

/**
 * 内容安全审核端口
 */
public interface ContentModerationPort {

    /** @return true = 通过 */
    boolean pass(String assetType, byte[] content);

}
