package com.haowugou.controller.auth;

import com.haowugou.application.user.UserAccountQuery;
import com.haowugou.security.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * 登录、登录态查询与 CSRF 令牌下发。
 *
 * <p>登出不在这里：由 {@code SecurityConfiguration} 的 {@code logout()} 处理
 * （{@code POST /api/auth/logout} 返回 204），会话失效与 Cookie 清理交给框架，
 * 自己写一遍容易漏掉其中一步。
 *
 * <p>登录用手工认证而不是 {@code formLogin}：请求体是 JSON，而 {@code formLogin}
 * 只认表单编码。手工认证要自己负责两件否则会被 {@code formLogin} 顺带做掉的事——
 * 换会话 ID（防会话固定）与把认证结果写进会话（否则下一个请求又是未登录）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserAccountQuery userAccountQuery;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    public AuthController(
            AuthenticationManager authenticationManager,
            UserAccountQuery userAccountQuery,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy) {
        this.authenticationManager = authenticationManager;
        this.userAccountQuery = userAccountQuery;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
    }

    /**
     * 账号密码登录。凭据不正确时由认证流程抛出异常，统一映射为 401。
     *
     * <p>认证通过后的顺序不能调换：先换会话 ID，再把认证结果写进（新的）会话。
     * 反过来写进的是旧会话，换 ID 时就丢了。
     */
    @PostMapping("/login")
    public AuthenticatedUserResponse login(
            @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                        request.username(), request.password()));

        sessionAuthenticationStrategy.onAuthentication(authentication, httpRequest, httpResponse);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        userAccountQuery.recordLogin(principal.userId(), LocalDateTime.now());
        return AuthenticatedUserResponse.from(userAccountQuery.toProfile(principal.user()));
    }

    /**
     * 当前登录者的身份与权限。
     *
     * <p>重新查库而不是直接用会话里的主体：账号可能在登录之后被停用或改了绑定门店，
     * 此时应当立刻反映出来（账号已停用会返回 404，由前端引导重新登录）。
     */
    @GetMapping("/me")
    public AuthenticatedUserResponse currentUser(@AuthenticationPrincipal AppUserPrincipal principal) {
        return AuthenticatedUserResponse.from(userAccountQuery.findProfile(principal.userId()));
    }

    /**
     * 下发 CSRF 令牌，登录前调用。
     *
     * <p>令牌由 {@code CsrfFilter} 放进请求属性，这里只是把它读出来返回；
     * 同一次响应里 {@code CookieCsrfTokenRepository} 会写下 {@code XSRF-TOKEN} Cookie。
     *
     * <p>属性名从请求里现取而不是写成 {@code @RequestAttribute} 的值：属性键是
     * {@code CsrfToken.class.getName()}，方法调用不是编译期常量，注解里放不了，
     * 手抄成字符串字面量则会在类改名时静默失效。
     */
    @GetMapping("/csrf")
    public CsrfTokenResponse csrfToken(HttpServletRequest httpRequest) {
        CsrfToken token = (CsrfToken) httpRequest.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            throw new IllegalStateException("CSRF 令牌不可用，请检查 CSRF 防护是否被关闭");
        }
        return CsrfTokenResponse.from(token);
    }
}
