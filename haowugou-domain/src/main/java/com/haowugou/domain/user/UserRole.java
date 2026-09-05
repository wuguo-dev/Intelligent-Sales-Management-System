package com.haowugou.domain.user;

/**
 * 登录账号角色，对应 {@code app_user.role_id}。
 *
 * <p>角色只有两级，且数字编码由数据库契约固定（{@code chk_app_user_role} 限定取值 1、2），
 * 所以 {@link #roleId()} 不是展示用的序号而是持久化口径，不能靠 {@code ordinal()} 顶替。
 *
 * <p>权限判定集中在本枚举的两个谓词上，不散落到各处 {@code if (role == ADMIN)}：
 * 新增角色时编译器会把所有需要重新表态的地方指出来。
 */
public enum UserRole {

    /** 管理员：可用全部功能，不绑门店（可跨门店操作）。 */
    ADMIN(1),

    /** 普通用户：绑定单一门店，只能查看商品售价、库存数量与所处仓库。 */
    USER(2);

    private final int roleId;

    UserRole(int roleId) {
        this.roleId = roleId;
    }

    /** 数据库里的角色编码。 */
    public int roleId() {
        return roleId;
    }

    /** 是否可以执行导入、撤销等写操作，以及查看跨门店数据。 */
    public boolean canManage() {
        return this == ADMIN;
    }

    /** 是否可以看到含税成本价与毛利等经营敏感字段。 */
    public boolean canViewCostAndProfit() {
        return this == ADMIN;
    }

    /**
     * 按数据库编码解析角色。
     *
     * @throws IllegalArgumentException 编码不是已知角色（库里出现了 CHECK 约束之外的值）
     */
    public static UserRole fromRoleId(int roleId) {
        for (UserRole role : values()) {
            if (role.roleId == roleId) {
                return role;
            }
        }
        throw new IllegalArgumentException("未知的角色编码：" + roleId);
    }
}
