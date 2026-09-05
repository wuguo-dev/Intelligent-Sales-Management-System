package com.haowugou.infrastructure.persistence.user;

import com.haowugou.domain.user.AppUser;
import com.haowugou.domain.user.UserRepository;
import com.haowugou.domain.user.UserRole;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 登录账号查询的 MyBatis 实现。
 *
 * <p>两条查询语句都带 {@code is_active = 1}：停用账号在这一层就读不出来，
 * 上层不需要（也不能）再判一次启用状态。
 */
@Repository
public class MybatisUserRepository implements UserRepository {

    private final AppUserMapper mapper;

    public MybatisUserRepository(AppUserMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<AppUser> findActiveByUsername(String username) {
        return Optional.ofNullable(mapper.findActiveByUsername(username)).map(this::toAppUser);
    }

    @Override
    public Optional<AppUser> findActiveById(long id) {
        return Optional.ofNullable(mapper.findActiveById(id)).map(this::toAppUser);
    }

    @Override
    public void touchLastLogin(long userId, LocalDateTime loginAt) {
        mapper.updateLastLoginAt(userId, loginAt);
    }

    /**
     * 只查启用账号，所以 {@code active} 恒为 true。
     *
     * <p>{@code AppUser} 的构造器会校验「管理员不绑门店、普通用户必须绑门店」，
     * 与数据库 {@code chk_app_user_store_scope} 同口径：这里映射错列会立刻炸，
     * 而不是产出一个门店过滤失效的账号。
     */
    private AppUser toAppUser(AppUserRow row) {
        return new AppUser(
                row.getId(),
                row.getUsername(),
                row.getPasswordHash(),
                row.getDisplayName(),
                UserRole.fromRoleId(row.getRoleId()),
                row.getStoreId(),
                true);
    }
}
