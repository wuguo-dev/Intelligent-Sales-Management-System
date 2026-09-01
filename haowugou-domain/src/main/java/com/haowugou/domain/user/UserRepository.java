package com.haowugou.domain.user;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 登录账号的持久化边界。
 *
 * <p>只读 + 一次登录时间回写。账号的增删改由开发人员直接操作数据库，
 * 不在本端口暴露，避免给应用层留下越权改角色的入口。
 */
public interface UserRepository {

    /**
     * 按登录名查启用状态的账号；账号不存在或已停用时返回空。
     *
     * <p>停用账号与不存在账号返回同一结果，调用方无法据此区分——认证失败的原因
     * 不应该通过接口行为泄露。
     *
     * <p>登录名区分大小写（{@code app_user.username} 用 {@code utf8mb4_bin} 排序规则）。
     */
    Optional<AppUser> findActiveByUsername(String username);

    /** 按主键查启用状态的账号；账号不存在或已停用时返回空。 */
    Optional<AppUser> findActiveById(long id);

    /**
     * 记录最近登录时间。
     *
     * <p>登录成功后调用，失败不影响登录结果——这是审计信息，不是认证的一部分。
     */
    void touchLastLogin(long userId, LocalDateTime loginAt);
}
