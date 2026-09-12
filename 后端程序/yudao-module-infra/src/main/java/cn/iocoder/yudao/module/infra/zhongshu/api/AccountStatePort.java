package cn.iocoder.yudao.module.infra.zhongshu.api;

/** Transactional guard: acquire before business locks to serialize new work with account closure. */
public interface AccountStatePort {
    void requireActiveForWrite(long accountId);
}
