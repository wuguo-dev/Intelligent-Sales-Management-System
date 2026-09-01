package com.haowugou.security;

import com.haowugou.domain.user.AppUser;
import com.haowugou.domain.user.UserRole;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 把领域账号包装成 Spring Security 的登录主体。
 *
 * <p>存在的意义是让下游拿到的是 {@link AppUser} 而不是一串 authority 字符串：
 * 门店范围判断（{@link AppUser#canAccessStore(long)}）与字段可见性判断
 * （{@link UserRole#canViewCostAndProfit()}）都是领域规则，不该在控制器里用
 * {@code hasRole("ADMIN")} 这类字符串比较重新表达一遍。
 *
 * <p>授权表达式仍需要 authority，所以同时给出 {@code ROLE_ADMIN}/{@code ROLE_USER}。
 */
public final class AppUserPrincipal implements UserDetails {

    /** authority 前缀，Spring Security 的 {@code hasRole} 会自动补这个前缀。 */
    private static final String ROLE_PREFIX = "ROLE_";

    private final AppUser user;

    public AppUserPrincipal(AppUser user) {
        this.user = Objects.requireNonNull(user, "账号不能为空");
    }

    /** 领域账号本体，供控制器与授权管理器做门店范围与字段可见性判断。 */
    public AppUser user() {
        return user;
    }

    public long userId() {
        return user.id();
    }

    public UserRole role() {
        return user.role();
    }

    /** 绑定门店；管理员为 null，代表可访问全部门店。 */
    public Long storeId() {
        return user.storeId();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(ROLE_PREFIX + user.role().name()));
    }

    @Override
    public String getPassword() {
        return user.passwordHash();
    }

    @Override
    public String getUsername() {
        return user.username();
    }

    /**
     * 账号是否可用。
     *
     * <p>恒为 true：停用账号在 SQL 层就查不出来（{@code is_active = 1}），
     * 能构造出主体的账号必然是启用的。
     */
    @Override
    public boolean isEnabled() {
        return user.active();
    }
}
