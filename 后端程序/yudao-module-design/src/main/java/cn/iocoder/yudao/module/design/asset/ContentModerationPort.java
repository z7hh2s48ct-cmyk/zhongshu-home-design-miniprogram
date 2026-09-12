package cn.iocoder.yudao.module.design.asset;

/**
 * 内容安全审核端口
 */
public interface ContentModerationPort {

    /** @return true = 通过 */
    boolean pass(String assetType, byte[] content);

    default Decision review(String assetType, byte[] content) {
        boolean accepted = pass(assetType, content);
        return new Decision(accepted, "stub", "", accepted ? "Pass" : "Block", "development");
    }

    record Decision(boolean accepted, String provider, String requestId, String suggestion, String policy) { }

}
