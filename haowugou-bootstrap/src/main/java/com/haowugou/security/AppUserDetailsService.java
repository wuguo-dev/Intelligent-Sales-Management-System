package com.haowugou.security;

import com.haowugou.application.user.UserAccountQuery;
import java.util.Objects;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * 把 {@link UserAccountQuery} 接到 Spring Security 的认证流程上。
 *
 * <p>这里只做类型转换，不做密码比对——比对由 {@code DaoAuthenticationProvider}
 * 用配置好的 {@code PasswordEncoder} 完成。
 */
public final class AppUserDetailsService implements UserDetailsService {

    private final UserAccountQuery userAccountQuery;

    public AppUserDetailsService(UserAccountQuery userAccountQuery) {
        this.userAccountQuery = Objects.requireNonNull(userAccountQuery);
    }

    /**
     * 账号不存在或已停用时抛 {@link UsernameNotFoundException}。
     *
     * <p>异常消息不区分「不存在」与「已停用」：登录失败的原因对外一律是 401，
     * 区分开会让接口变成账号枚举工具。
     */
    @Override
    public UserDetails loadUserByUsername(String username) {
        return userAccountQuery.loadForAuthentication(username)
                .map(AppUserPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("账号不存在或已停用"));
    }
}
