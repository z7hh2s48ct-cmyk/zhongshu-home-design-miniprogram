package cn.iocoder.yudao.module.identity.account;

import cn.iocoder.yudao.module.infra.zhongshu.api.ProfileAvatarPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;

@Service
public class AccountProfileService {
    private final JdbcTemplate jdbc;
    private final ProfileAvatarPort avatars;

    public AccountProfileService(DataSource dataSource, ProfileAvatarPort avatars) {
        jdbc = new JdbcTemplate(dataSource);
        this.avatars = avatars;
    }

    public boolean update(long accountId, String nickname, String avatarAssetId) {
        String name = nickname == null ? "" : nickname.strip();
        if (name.isEmpty() || name.length() > 32 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("昵称须为1至32个字符，不能包含控制字符");
        }
        if (avatarAssetId == null) {
            return jdbc.update("UPDATE account SET nickname = ?, update_time = now() WHERE id = ? AND deleted = FALSE",
                    name, accountId) == 1;
        }
        if (!avatarAssetId.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("头像编号无效");
        long assetId;
        try { assetId = Long.parseLong(avatarAssetId); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("头像编号无效"); }
        avatars.requireOwnedAcceptedAvatar(accountId, assetId);
        // Reuse the existing avatar field; persist an asset reference, never an expiring URL or local temp path.
        return jdbc.update("UPDATE account SET nickname = ?, avatar = ?, update_time = now() WHERE id = ? AND deleted = FALSE",
                name, "asset:" + assetId, accountId) == 1;
    }

    public static String avatarAssetId(String avatar) {
        return avatar != null && avatar.matches("asset:[1-9][0-9]{0,18}") ? avatar.substring(6) : null;
    }
}
