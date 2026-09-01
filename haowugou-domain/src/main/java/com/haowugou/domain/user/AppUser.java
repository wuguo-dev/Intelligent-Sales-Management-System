package com.haowugou.domain.user;

/**
 * 系统登录账号，对应 {@code app_user}。
 *
 * <p>账号由开发人员直接写库，没有注册链路，所以本记录只承担「读出来判权限」的职责，
 * 不提供改密码、改角色等行为。
 *
 * <p>构造器把门店范围口径与数据库 {@code chk_app_user_store_scope} 对齐：
 * 管理员必须不绑门店（{@code storeId} 为 null 表示全部门店），普通用户必须绑门店。
 * 两侧都拦是有意为之——数据库挡手工建账号填错，这里挡实现层查询漏字段或映射错列，
 * 避免出现一个 {@code storeId} 为 null 的「普通用户」，让门店过滤静默失效。
 *
 * <p>{@code passwordHash} 只在认证时与用户输入比对，不对外输出（响应模型不含该字段）。
 */
public record AppUser(
        long id,
        String username,
        String passwordHash,
        String displayName,
        UserRole role,
        Long storeId,
        boolean active) {

    public AppUser {
        if (id <= 0) {
            throw new IllegalArgumentException("账号主键必须为正数，当前为 " + id);
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("登录名不能为空");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("密码哈希不能为空");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("展示名不能为空");
        }
        if (role == null) {
            throw new IllegalArgumentException("角色不能为空");
        }
        if (role == UserRole.ADMIN && storeId != null) {
            throw new IllegalArgumentException("管理员不能绑定门店，当前 storeId=" + storeId);
        }
        if (role == UserRole.USER && storeId == null) {
            throw new IllegalArgumentException("普通用户必须绑定门店");
        }
        if (storeId != null && storeId <= 0) {
            throw new IllegalArgumentException("门店 ID 必须为正数，当前为 " + storeId);
        }
    }

    /**
     * 该账号是否有权访问指定门店的数据。
     *
     * <p>注意这是「缩小可访问范围」的判断，不替代底层查询里的 {@code storeId} 条件
     * （架构规范 §9）：查询依旧必须把 storeId 下传到 SQL。
     */
    public boolean canAccessStore(long targetStoreId) {
        return role.canManage() || (storeId != null && storeId == targetStoreId);
    }
}
