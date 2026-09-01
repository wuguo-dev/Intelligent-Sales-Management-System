package com.haowugou.controller.auth;

import com.haowugou.application.user.UserProfileResult;
import com.haowugou.domain.store.Store;

/**
 * 登录态响应，登录成功与 {@code GET /api/auth/me} 共用。
 *
 * <p>不含密码哈希：这个 record 里没有该字段，序列化就不可能带出去。
 *
 * <p>{@code store} 为 null 表示管理员（不绑门店、可跨门店）。
 * {@code canManage} 与 {@code canViewCostAndProfit} 直接给出前端要的判断结论，
 * 免得前端自己按 {@code roleId} 再推一遍权限规则、两边口径走偏。
 *
 * @param userId 账号标识
 * @param username 登录名
 * @param displayName 展示名
 * @param roleId 角色编码：1 管理员，2 普通用户
 * @param role 角色名称：ADMIN 或 USER
 * @param store 绑定门店；管理员为 null
 * @param canManage 是否可执行导入、撤销等写操作
 * @param canViewCostAndProfit 是否可看到含税成本价与毛利字段
 */
public record AuthenticatedUserResponse(
        long userId,
        String username,
        String displayName,
        int roleId,
        String role,
        StoreResponse store,
        boolean canManage,
        boolean canViewCostAndProfit) {

    static AuthenticatedUserResponse from(UserProfileResult profile) {
        return new AuthenticatedUserResponse(
                profile.userId(),
                profile.username(),
                profile.displayName(),
                profile.role().roleId(),
                profile.role().name(),
                StoreResponse.from(profile.store()),
                profile.canManage(),
                profile.canViewCostAndProfit());
    }

    /** 登录账号绑定的门店。 */
    public record StoreResponse(Long id, String storeCode, String storeName) {

        static StoreResponse from(Store store) {
            return store == null
                    ? null
                    : new StoreResponse(store.id(), store.storeCode(), store.storeName());
        }
    }
}
