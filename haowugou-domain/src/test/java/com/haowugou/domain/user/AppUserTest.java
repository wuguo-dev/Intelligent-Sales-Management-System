package com.haowugou.domain.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AppUserTest {

    private static final String HASH = "$2a$10$f2BIX/9aPOAIL58RPrNzve";

    /**
     * 与数据库 chk_app_user_store_scope 同口径：管理员不绑门店、普通用户必须绑门店。
     * 这里再拦一次是防实现层映射漏字段——storeId 为 null 的「普通用户」会让门店过滤静默失效。
     */
    @Test
    void enforcesStoreScopeMatchingTheDatabaseCheck() {
        assertThrows(IllegalArgumentException.class,
                () -> new AppUser(1L, "admin", HASH, "管理员", UserRole.ADMIN, 1L, true));
        assertThrows(IllegalArgumentException.class,
                () -> new AppUser(2L, "user", HASH, "用户", UserRole.USER, null, true));
    }

    @Test
    void rejectsBlankOrNonPositiveFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new AppUser(0L, "admin", HASH, "管理员", UserRole.ADMIN, null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new AppUser(1L, "  ", HASH, "管理员", UserRole.ADMIN, null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new AppUser(1L, "admin", " ", "管理员", UserRole.ADMIN, null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new AppUser(1L, "admin", HASH, "", UserRole.ADMIN, null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new AppUser(1L, "admin", HASH, "管理员", null, null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new AppUser(2L, "user", HASH, "用户", UserRole.USER, 0L, true));
    }

    /** 管理员对任意门店都放行；普通用户只放行绑定的那一家。 */
    @Test
    void scopesStoreAccessByRole() {
        AppUser admin = new AppUser(1L, "admin", HASH, "管理员", UserRole.ADMIN, null, true);
        AppUser user = new AppUser(2L, "user", HASH, "用户", UserRole.USER, 7L, true);

        assertTrue(admin.canAccessStore(7L));
        assertTrue(admin.canAccessStore(99L));
        assertTrue(user.canAccessStore(7L));
        assertFalse(user.canAccessStore(99L));
    }

    /** roleId 是持久化口径，不能退化成 ordinal()。 */
    @Test
    void mapsRoleIdBothWays() {
        assertEquals(1, UserRole.ADMIN.roleId());
        assertEquals(2, UserRole.USER.roleId());
        assertEquals(UserRole.ADMIN, UserRole.fromRoleId(1));
        assertEquals(UserRole.USER, UserRole.fromRoleId(2));
        assertThrows(IllegalArgumentException.class, () -> UserRole.fromRoleId(0));
        assertThrows(IllegalArgumentException.class, () -> UserRole.fromRoleId(3));
    }

    @Test
    void grantsWriteAndCostVisibilityToAdminOnly() {
        assertTrue(UserRole.ADMIN.canManage());
        assertTrue(UserRole.ADMIN.canViewCostAndProfit());
        assertFalse(UserRole.USER.canManage());
        assertFalse(UserRole.USER.canViewCostAndProfit());
    }
}
