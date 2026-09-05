package com.haowugou.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haowugou.application.user.UserAccountQuery;
import com.haowugou.domain.user.UserRole;
import com.haowugou.security.AppUserDetailsService;
import com.haowugou.security.ProblemDetailAccessDeniedHandler;
import com.haowugou.security.ProblemDetailAuthenticationEntryPoint;
import com.haowugou.security.StoreScopeAuthorizationManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.http.HttpStatus;

/**
 * 认证与授权的组装点。
 *
 * <p>会话 Cookie 认证：登录成功后 {@code JSESSIONID} 由浏览器自动携带，前端不需要
 * 自己存令牌（存 localStorage 的令牌会被 XSS 直接读走）。
 *
 * <p>权限口径（详见 CLAUDE.md 「用户与权限切片」）：
 * <ul>
 *   <li>管理员：全部接口、全部门店。</li>
 *   <li>普通用户：只能读自己门店的商品与仓库，且看不到成本与毛利字段。</li>
 * </ul>
 *
 * <p>授权规则的顺序即匹配顺序，越具体的规则必须写在越前面。最后一条
 * {@code anyRequest().denyAll()} 是默认拒绝：新增接口如果忘了配规则，
 * 结果是不可访问而不是对所有人敞开。
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /** 角色名，与 {@link UserRole} 同名；{@code hasRole} 会自动补 {@code ROLE_} 前缀。 */
    private static final String ADMIN = UserRole.ADMIN.name();

    /**
     * BCrypt strength 10：与 {@code database/migration/2026-09-01-app-user.sql} 里
     * 种子账号哈希的生成参数一致。改这个值不会让已有哈希失效（强度记录在哈希串里），
     * 只影响以后新生成的哈希。
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    AppUserDetailsService appUserDetailsService(UserAccountQuery userAccountQuery) {
        return new AppUserDetailsService(userAccountQuery);
    }

    /**
     * 显式装配认证管理器，而不是依赖自动配置。
     *
     * <p>{@code hideUserNotFoundExceptions} 保持默认的 true：账号不存在与密码错误
     * 都抛 {@code BadCredentialsException}，对外都是 401，避免接口能枚举出哪些账号存在。
     */
    @Bean
    AuthenticationManager authenticationManager(
            AppUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    StoreScopeAuthorizationManager storeScopeAuthorizationManager() {
        return new StoreScopeAuthorizationManager();
    }

    /**
     * 认证态的存放位置，显式装配是因为 {@code AuthController} 手工登录时要往里写。
     *
     * <p>两级委派与 Spring Security 6 的默认行为一致：请求属性先放一份（同一次请求内后续
     * 过滤器立即可见），会话再存一份（跨请求保持登录）。只留会话那一份会让登录响应自身
     * 处在「已认证但当次请求读不到」的状态。
     */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    /**
     * 登录成功时换掉会话 ID，防会话固定攻击。
     *
     * <p>{@code AuthController} 手工调用它——{@code formLogin} 已关闭，框架不会代劳。
     * CSRF 令牌存在 Cookie 而不是会话里，所以换 ID 不会让刚取到的令牌失效。
     */
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            StoreScopeAuthorizationManager storeScope,
            SecurityContextRepository securityContextRepository,
            ObjectMapper objectMapper) throws Exception {
        http
                .authorizeHttpRequests(requests -> requests
                        // 登录与 CSRF 令牌获取：未登录也必须能访问，否则谁都进不来。
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers("/api/auth/**").authenticated()

                        // 写操作与批次管理：管理员专用。
                        .requestMatchers(HttpMethod.POST, "/api/stores/*/inventory/import").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/stores/*/sales/import").hasRole(ADMIN)
                        .requestMatchers("/api/stores/*/import-batches/**").hasRole(ADMIN)

                        // 商品与仓库查询：登录即可，但只能查自己有权的门店。
                        .requestMatchers(HttpMethod.GET, "/api/stores/{storeId}/products/**").access(storeScope)
                        .requestMatchers(HttpMethod.GET, "/api/stores/{storeId}/products").access(storeScope)
                        .requestMatchers(HttpMethod.GET, "/api/stores/{storeId}/warehouses").access(storeScope)

                        // storeId 在查询参数里的旧链路接口：路径上没有模板变量，
                        // 门店范围无法在这一层收紧（普通用户可传任意 storeId），
                        // 因此整段限制为管理员专用。
                        .requestMatchers(HttpMethod.GET, "/api/stores").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/sales/daily").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/inventory").hasRole(ADMIN)

                        // 默认拒绝：漏配规则的新接口不可访问，而不是对所有人开放。
                        .anyRequest().denyAll())

                // CSRF 保持开启：会话认证靠 Cookie 自动携带，没有 CSRF 防护时
                // 第三方站点可以借用户的登录态发起导入或撤销。
                // withHttpOnlyFalse 让前端 JS 能读 XSRF-TOKEN 并回填到请求头。
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))

                // 过滤器链与 AuthController 必须用同一个仓库实例，否则登录写进去的
                // 认证态与后续请求读的不是同一处。
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository))

                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        // 登录后换新会话 ID，防会话固定攻击。
                        .sessionFixation(fixation -> fixation.changeSessionId()))

                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))

                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new ProblemDetailAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new ProblemDetailAccessDeniedHandler(objectMapper)))

                // 纯 JSON 后端：关掉表单登录与 Basic，避免浏览器弹原生登录框、
                // 也避免 Basic 认证绕过 CSRF 防护。
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .anonymous(anonymous -> anonymous.disable());

        return http.build();
    }
}
