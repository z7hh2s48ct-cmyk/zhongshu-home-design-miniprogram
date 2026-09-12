package cn.iocoder.yudao.module.infra.zhongshu.api;

/** Identity validates avatar ownership through the existing design asset lifecycle. */
public interface ProfileAvatarPort {
    void requireOwnedAcceptedAvatar(long accountId, long assetId);
}
