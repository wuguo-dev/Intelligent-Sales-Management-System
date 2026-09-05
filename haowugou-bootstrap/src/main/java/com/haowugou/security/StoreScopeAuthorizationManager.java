package com.haowugou.security;

import com.haowugou.domain.user.AppUser;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * 按 URL 里的 {@code storeId} 判断当前登录者是否有权访问该门店。
 *
 * <p>管理员放行任意门店；普通用户只放行绑定的那一家。
 *
 * <p>这只是「缩小可访问门店范围」的一层，不替代底层查询里的 {@code storeId} 条件
 * （架构规范 §9）：SQL 依旧必须带门店过滤，否则这里放行之后仍会查出全部门店数据。
 *
 * <p>只能保护路径里带 {@code {storeId}} 的接口。把 storeId 放查询参数的接口
 * （{@code /api/sales/daily?storeId=}）拿不到模板变量，一律限制为管理员专用。
 *
 * <p>写成显式类而不是 lambda：{@code AuthorizationManager} 在不同 Spring Security
 * 版本里抽象方法不同（6.x 是 {@code check}，7.x 改成 {@code authorize}），
 * 显式实现能让编译器把该覆盖哪个方法直接指出来。
 */
public final class StoreScopeAuthorizationManager
        implements AuthorizationManager<RequestAuthorizationContext> {

    /** URI 模板变量名，需与控制器 {@code @RequestMapping("/api/stores/{storeId}")} 一致。 */
    private static final String STORE_ID_VARIABLE = "storeId";

    @Override
    public AuthorizationDecision check(
            Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        AppUser user = currentUser(authentication);
        if (user == null) {
            return new AuthorizationDecision(false);
        }
        Long storeId = parseStoreId(context);
        if (storeId == null) {
            // 路径里没有可解析的 storeId：要么规则配错了路径，要么 storeId 不是数字。
            // 两种情况都拒绝——放行会让门店范围失去约束。
            return new AuthorizationDecision(false);
        }
        return new AuthorizationDecision(user.canAccessStore(storeId));
    }

    private AppUser currentUser(Supplier<Authentication> authentication) {
        Authentication current = authentication.get();
        if (current == null || !current.isAuthenticated()) {
            return null;
        }
        // 匿名认证的 principal 是字符串，不是本系统的登录主体。
        return current.getPrincipal() instanceof AppUserPrincipal principal ? principal.user() : null;
    }

    private Long parseStoreId(RequestAuthorizationContext context) {
        String raw = context.getVariables().get(STORE_ID_VARIABLE);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException exception) {
            // 非数字门店 ID 交给控制器层报 400 是没机会的——这里已经拒绝。
            // 拒绝比放行安全：能走到控制器的一定是通过了门店校验的请求。
            return null;
        }
    }
}
