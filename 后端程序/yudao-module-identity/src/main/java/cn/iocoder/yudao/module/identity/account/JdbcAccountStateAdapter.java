package cn.iocoder.yudao.module.identity.account;

import cn.iocoder.yudao.module.infra.zhongshu.api.AccountStatePort;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;

@Component
public class JdbcAccountStateAdapter implements AccountStatePort {
    private final JdbcTemplate jdbc;
    public JdbcAccountStateAdapter(DataSource dataSource) { jdbc = new JdbcTemplate(dataSource); }
    @Override public void requireActiveForWrite(long accountId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("account write guard requires transaction");
        var rows = jdbc.queryForList("SELECT status FROM account WHERE id=? AND deleted=FALSE FOR SHARE", String.class, accountId);
        if (rows.isEmpty() || !"ACTIVE".equals(rows.get(0))) throw new ServiceException(403, "账号已停用或关闭");
    }
}
