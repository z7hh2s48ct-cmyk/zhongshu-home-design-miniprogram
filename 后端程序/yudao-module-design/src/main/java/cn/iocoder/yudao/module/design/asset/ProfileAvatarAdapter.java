package cn.iocoder.yudao.module.design.asset;

import cn.iocoder.yudao.module.infra.zhongshu.api.ProfileAvatarPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;

@Component
public class ProfileAvatarAdapter implements ProfileAvatarPort {
    private final JdbcTemplate jdbc;

    public ProfileAvatarAdapter(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void requireOwnedAcceptedAvatar(long accountId, long assetId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM asset WHERE id = ? AND owner_user_id = ? "
                + "AND asset_type = 'USER_AVATAR' AND upload_status = 'ACCEPTED' "
                + "AND security_scan_status = 'PASSED' AND moderation_status = 'PASSED' "
                + "AND declared_mime IN ('image/jpeg', 'image/png') AND size_bytes <= 2097152 AND deleted = FALSE",
                Integer.class, assetId, accountId);
        if (count == null || count != 1) throw new AccessDeniedException("头像不存在、非本人所有或尚未通过校验");
    }
}
