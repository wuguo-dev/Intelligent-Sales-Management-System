package com.haowugou.application.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haowugou.application.user.exception.InvalidUserQueryException;
import com.haowugou.application.user.exception.UserAccountNotFoundException;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import com.haowugou.domain.user.AppUser;
import com.haowugou.domain.user.UserRepository;
import com.haowugou.domain.user.UserRole;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserAccountQueryTest {

    private static final long STORE_ID = 1L;
    private static final long ADMIN_ID = 10L;
    private static final long USER_ID = 20L;
    private static final Store STORE = new Store(STORE_ID, "S-001", "城南店");
    private static final LocalDateTime LOGIN_AT = LocalDateTime.of(2026, 9, 1, 9, 30);

    @Test
    void loadsActiveAccountForAuthentication() {
        UserAccountQuery query = new UserAccountQuery(new UserRepositoryStub(), activeStores());

        Optional<AppUser> found = query.loadForAuthentication("admin");

        assertTrue(found.isPresent());
        assertEquals(ADMIN_ID, found.get().id());
        assertEquals(UserRole.ADMIN, found.get().role());
    }

    /** 停用账号与不存在账号返回同一结果：认证失败的原因不通过接口行为泄露。 */
    @Test
    void treatsMissingAndDisabledAccountsAlike() {
        UserAccountQuery query = new UserAccountQuery(new UserRepositoryStub(), activeStores());

        assertTrue(query.loadForAuthentication("nobody").isEmpty());
        assertTrue(query.loadForAuthentication("disabled").isEmpty());
    }

    /** 登录名区分大小写：数据库唯一键用 utf8mb4_bin，这里归一会让登录与建号口径不一致。 */
    @Test
    void keepsUsernameCaseSensitive() {
        UserAccountQuery query = new UserAccountQuery(new UserRepositoryStub(), activeStores());

        assertTrue(query.loadForAuthentication("admin").isPresent());
        assertTrue(query.loadForAuthentication("ADMIN").isEmpty());
    }

    /** 非法登录名不该打到数据库：这类输入不可能匹配到账号。 */
    @Test
    void rejectsUnusableUsernameWithoutQueryingTheRepository() {
        UserAccountQuery query = new UserAccountQuery(failingUsers(), activeStores());

        assertTrue(query.loadForAuthentication(null).isEmpty());
        assertTrue(query.loadForAuthentication("   ").isEmpty());
        assertTrue(query.loadForAuthentication("a".repeat(65)).isEmpty());
    }

    @Test
    void trimsUsernameBeforeLookup() {
        UserRepositoryStub users = new UserRepositoryStub();
        UserAccountQuery query = new UserAccountQuery(users, activeStores());

        assertTrue(query.loadForAuthentication("  admin  ").isPresent());
        assertEquals("admin", users.lastUsername);
    }

    /** 管理员不绑门店：身份视图里 store 为 null，代表可跨门店。 */
    @Test
    void describesAdminAsUnboundToAnyStore() {
        UserAccountQuery query = new UserAccountQuery(new UserRepositoryStub(), activeStores());

        UserProfileResult profile = query.findProfile(ADMIN_ID);

        assertEquals(ADMIN_ID, profile.userId());
        assertEquals("admin", profile.username());
        assertEquals("系统管理员", profile.displayName());
        assertEquals(UserRole.ADMIN, profile.role());
        assertNull(profile.store());
        assertTrue(profile.canManage());
        assertTrue(profile.canViewCostAndProfit());
    }

    /** 普通用户带上绑定门店的名称，供前端定死默认门店。 */
    @Test
    void resolvesBoundStoreForNormalUser() {
        UserAccountQuery query = new UserAccountQuery(new UserRepositoryStub(), activeStores());

        UserProfileResult profile = query.findProfile(USER_ID);

        assertEquals(UserRole.USER, profile.role());
        assertEquals(STORE, profile.store());
        assertFalse(profile.canManage());
        assertFalse(profile.canViewCostAndProfit());
    }

    /**
     * 门店被停用时留 null 而不是抛异常：外键保证门店记录存在，
     * 停用门店不该把已登录用户彻底锁死在门外。
     */
    @Test
    void keepsProfileUsableWhenBoundStoreIsDisabled() {
        StoreRepository noStores = new StoreRepository() {
            @Override
            public List<Store> findAllActive() {
                return List.of();
            }

            @Override
            public boolean existsActiveById(long storeId) {
                return false;
            }
        };
        UserAccountQuery query = new UserAccountQuery(new UserRepositoryStub(), noStores);

        UserProfileResult profile = query.findProfile(USER_ID);

        assertEquals(UserRole.USER, profile.role());
        assertNull(profile.store());
    }

    /** 会话还在但账号已被停用/删除：这是异常情况，对外 404。 */
    @Test
    void reportsDisappearedAccountAsNotFound() {
        UserAccountQuery query = new UserAccountQuery(new UserRepositoryStub(), activeStores());

        assertThrows(UserAccountNotFoundException.class, () -> query.findProfile(999L));
    }

    @Test
    void rejectsNonPositiveUserId() {
        UserAccountQuery query = new UserAccountQuery(failingUsers(), activeStores());

        assertThrows(InvalidUserQueryException.class, () -> query.findProfile(0L));
        assertThrows(InvalidUserQueryException.class, () -> query.findProfile(-1L));
        assertThrows(InvalidUserQueryException.class, () -> query.recordLogin(0L, LOGIN_AT));
    }

    @Test
    void recordsLastLoginTime() {
        UserRepositoryStub users = new UserRepositoryStub();
        UserAccountQuery query = new UserAccountQuery(users, activeStores());

        query.recordLogin(ADMIN_ID, LOGIN_AT);

        assertEquals(List.of(ADMIN_ID + "@" + LOGIN_AT), users.loginTouches);
    }

    /** 最近登录时间是审计字段：写失败不该把已通过认证的登录变成失败。 */
    @Test
    void swallowsFailureWhenRecordingLastLogin() {
        UserRepositoryStub users = new UserRepositoryStub();
        users.failOnTouch = true;
        UserAccountQuery query = new UserAccountQuery(users, activeStores());

        query.recordLogin(ADMIN_ID, LOGIN_AT);
    }

    private StoreRepository activeStores() {
        return new StoreRepository() {
            @Override
            public List<Store> findAllActive() {
                return List.of(STORE);
            }

            @Override
            public boolean existsActiveById(long storeId) {
                return storeId == STORE_ID;
            }
        };
    }

    private UserRepository failingUsers() {
        UserRepositoryStub stub = new UserRepositoryStub();
        stub.failOnAccess = true;
        return stub;
    }

    /** 内存账号表：admin 为管理员，store1user 绑定门店，disabled 为停用账号。 */
    private static final class UserRepositoryStub implements UserRepository {

        private final List<String> loginTouches = new ArrayList<>();
        private String lastUsername;
        private boolean failOnAccess;
        private boolean failOnTouch;

        @Override
        public Optional<AppUser> findActiveByUsername(String username) {
            if (failOnAccess) {
                throw new AssertionError("非法登录名不应查询账号");
            }
            lastUsername = username;
            if ("admin".equals(username)) {
                return Optional.of(admin());
            }
            if ("store1user".equals(username)) {
                return Optional.of(normalUser());
            }
            // disabled 命中的是停用账号，端口只返回启用账号，所以同样是空。
            return Optional.empty();
        }

        @Override
        public Optional<AppUser> findActiveById(long id) {
            if (failOnAccess) {
                throw new AssertionError("非法账号ID不应查询账号");
            }
            if (id == ADMIN_ID) {
                return Optional.of(admin());
            }
            if (id == USER_ID) {
                return Optional.of(normalUser());
            }
            return Optional.empty();
        }

        @Override
        public void touchLastLogin(long userId, LocalDateTime loginAt) {
            if (failOnTouch) {
                throw new IllegalStateException("数据库写入失败");
            }
            loginTouches.add(userId + "@" + loginAt);
        }

        private AppUser admin() {
            return new AppUser(ADMIN_ID, "admin", "$2a$10$hash", "系统管理员", UserRole.ADMIN, null, true);
        }

        private AppUser normalUser() {
            return new AppUser(
                    USER_ID, "store1user", "$2a$10$hash", "门店查询员", UserRole.USER, STORE_ID, true);
        }
    }
}
