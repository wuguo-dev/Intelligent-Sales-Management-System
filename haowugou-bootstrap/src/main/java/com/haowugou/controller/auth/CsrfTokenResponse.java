package com.haowugou.controller.auth;

import org.springframework.security.web.csrf.CsrfToken;

/**
 * CSRF 令牌响应。
 *
 * <p>登录前先 {@code GET /api/auth/csrf} 拿一次：这一步会顺便下发 {@code XSRF-TOKEN}
 * Cookie，之后前端把 {@code token} 放进 {@code headerName} 指定的请求头即可。
 *
 * @param token 令牌值
 * @param headerName 应放入的请求头名（默认 X-XSRF-TOKEN）
 * @param parameterName 表单提交时的参数名（默认 _csrf）
 */
public record CsrfTokenResponse(String token, String headerName, String parameterName) {

    static CsrfTokenResponse from(CsrfToken token) {
        return new CsrfTokenResponse(
                token.getToken(), token.getHeaderName(), token.getParameterName());
    }
}
