package com.haowugou.application.user;

import com.haowugou.domain.store.Store;
import com.haowugou.domain.user.UserRole;

/**
 * 当前登录者的身份视图，供 {@code GET /api/auth/me} 输出。
 *
 * <p>不含 {@code passwordHash}：结果模型里没有这个字段，序列化就不可能带出去。
 *
 * <p>{@code store} 为 null 表示管理员（不绑门店、可跨门店）。普通用户一定带门店，
 * 前端据此决定默认门店与是否显示门店切换入口。
 */
public record UserProfileResult(
        long userId,
        String username,
        String displayName,
        UserRole role,
        Store store) {

    /** 是否可以执行导入、撤销等写操作。 */
    public boolean canManage() {
        return role.canManage();
    }

    /** 是否可以看到含税成本价与毛利字段。 */
    public boolean canViewCostAndProfit() {
        return role.canViewCostAndProfit();
    }
}
