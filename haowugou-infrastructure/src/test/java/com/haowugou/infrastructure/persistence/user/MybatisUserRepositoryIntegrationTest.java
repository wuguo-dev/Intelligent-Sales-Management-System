package com.haowugou.infrastructure.persistence.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haowugou.domain.user.AppUser;
import com.haowugou.domain.user.UserRole;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 真实 MySQL 集成测试：验证 app_user 的列映射、启用过滤、大小写敏感与数据库不变量。 */
class MybatisUserRepositoryIntegrationTest {

    private static final String ADMIN_HASH = "$2a$10$f2BIX/9aPOAIL58RPrNzvecKrreO9ZAXgtSapMle20HHrXcAn4gy2";

    private SqlSession session;
    private Connection connection;
    private MybatisUserRepository users;
    private TestIds ids;

    @BeforeEach
    void setUp() throws Exception {
        String password = System.getenv("HAOWUGOU_DB_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "设置 HAOWUGOU_DB_PASSWORD 后执行真实 MySQL 集成测试");
        String url = System.getenv().getOrDefault(
                "HAOWUGOU_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/haowugou?useUnicode=true&characterEncoding=UTF-8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        String username = System.getenv().getOrDefault("HAOWUGOU_DB_USERNAME", "root");

        UnpooledDataSource dataSource = new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver", url, username, password);
        Configuration configuration = new Configuration(new Environment(
                "mysql-integration-test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        addMapperXml(configuration, "mapper/AppUserMapper.xml");
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(false);

        ids = TestIds.create();
        connection = session.getConnection();
        insertFixtures(connection, ids);
        users = new MybatisUserRepository(session.getMapper(AppUserMapper.class));
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            try {
                connection.rollback();
            } catch (SQLException exception) {
                throw new IllegalStateException("回滚 MySQL 集成测试数据失败", exception);
            } finally {
                session.close();
            }
        }
    }

    /** 管理员：store_id 为 NULL 必须映射成 null 而不是被拆箱成 0。 */
    @Test
    void mapsAdminWithoutStoreBinding() {
        AppUser admin = users.findActiveByUsername(ids.adminName()).orElseThrow();

        assertEquals(ids.admin(), admin.id());
        assertEquals(ids.adminName(), admin.username());
        assertEquals(ADMIN_HASH, admin.passwordHash());
        assertEquals("集成测试管理员-" + ids.suffix(), admin.displayName());
        assertEquals(UserRole.ADMIN, admin.role());
        assertNull(admin.storeId());
        assertTrue(admin.active());
    }

    @Test
    void mapsNormalUserWithBoundStore() {
        AppUser user = users.findActiveByUsername(ids.userName()).orElseThrow();

        assertEquals(ids.user(), user.id());
        assertEquals(UserRole.USER, user.role());
        assertEquals(Long.valueOf(ids.store()), user.storeId());
        assertTrue(user.canAccessStore(ids.store()));
    }

    @Test
    void findsSameAccountById() {
        AppUser byName = users.findActiveByUsername(ids.userName()).orElseThrow();
        AppUser byId = users.findActiveById(ids.user()).orElseThrow();

        assertEquals(byName, byId);
    }

    /** 停用账号在这一层就读不出来，上层不需要再判启用状态。 */
    @Test
    void hidesDisabledAccounts() {
        assertTrue(users.findActiveByUsername(ids.disabledName()).isEmpty());
        assertTrue(users.findActiveById(ids.disabled()).isEmpty());
    }

    @Test
    void returnsEmptyForUnknownAccount() {
        assertTrue(users.findActiveByUsername("no-such-user-" + ids.suffix()).isEmpty());
        assertTrue(users.findActiveById(ids.base() + 999_999L).isEmpty());
    }

    /** username 列用 utf8mb4_bin：登录名区分大小写，不需要在 SQL 里加 BINARY。 */
    @Test
    void treatsUsernameAsCaseSensitive() {
        assertTrue(users.findActiveByUsername(ids.adminName()).isPresent());
        assertTrue(users.findActiveByUsername(ids.adminName().toUpperCase()).isEmpty());
    }

    @Test
    void writesLastLoginTime() throws SQLException {
        LocalDateTime loginAt = LocalDateTime.of(2026, 9, 1, 10, 15, 30);

        users.touchLastLogin(ids.admin(), loginAt);

        assertEquals(loginAt, readLastLoginAt(ids.admin()));
    }

    /** 登录名唯一：同名账号必须被 uk_app_user_username 拦住。 */
    @Test
    void rejectsDuplicateUsername() {
        assertThrows(SQLException.class, () -> update(connection,
                "INSERT INTO app_user (id, username, password_hash, display_name, role_id, store_id) "
                        + "VALUES (?, ?, ?, ?, 1, NULL)",
                ids.base() + 500L, ids.adminName(), ADMIN_HASH, "重名管理员"));
    }

    /**
     * chk_app_user_store_scope：管理员不能绑门店、普通用户必须绑门店。
     * 这条不变量在数据库层，手工建账号填错时立刻报错，而不是运行期出现越权账号。
     */
    @Test
    void enforcesStoreScopeInvariantAtDatabaseLevel() {
        assertThrows(SQLException.class, () -> update(connection,
                "INSERT INTO app_user (id, username, password_hash, display_name, role_id, store_id) "
                        + "VALUES (?, ?, ?, ?, 1, ?)",
                ids.base() + 501L, "bad-admin-" + ids.suffix(), ADMIN_HASH, "绑了门店的管理员", ids.store()));
        assertThrows(SQLException.class, () -> update(connection,
                "INSERT INTO app_user (id, username, password_hash, display_name, role_id, store_id) "
                        + "VALUES (?, ?, ?, ?, 2, NULL)",
                ids.base() + 502L, "bad-user-" + ids.suffix(), ADMIN_HASH, "没绑门店的普通用户"));
    }

    /** chk_app_user_role：角色编码只允许 1、2，避免出现枚举解析不了的第三种角色。 */
    @Test
    void rejectsUnknownRoleId() {
        assertThrows(SQLException.class, () -> update(connection,
                "INSERT INTO app_user (id, username, password_hash, display_name, role_id, store_id) "
                        + "VALUES (?, ?, ?, ?, 3, NULL)",
                ids.base() + 503L, "bad-role-" + ids.suffix(), ADMIN_HASH, "未知角色"));
    }

    private LocalDateTime readLastLoginAt(long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT last_login_at FROM app_user WHERE id = ?")) {
            statement.setObject(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                java.sql.Timestamp timestamp = result.getTimestamp("last_login_at");
                assertNotNull(timestamp);
                return timestamp.toLocalDateTime();
            }
        }
    }

    private void addMapperXml(Configuration configuration, String resource) throws Exception {
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    private void insertFixtures(Connection connection, TestIds ids) throws SQLException {
        update(connection, "INSERT INTO store (id, store_code, store_name, is_active) VALUES (?, ?, ?, 1)",
                ids.store(), "IT-U1-" + ids.suffix(), "集成测试账号门店-" + ids.suffix());
        update(connection, "INSERT INTO app_user "
                        + "(id, username, password_hash, display_name, role_id, store_id, is_active) VALUES "
                        + "(?, ?, ?, ?, 1, NULL, 1), (?, ?, ?, ?, 2, ?, 1), (?, ?, ?, ?, 1, NULL, 0)",
                ids.admin(), ids.adminName(), ADMIN_HASH, "集成测试管理员-" + ids.suffix(),
                ids.user(), ids.userName(), ADMIN_HASH, "集成测试店员-" + ids.suffix(), ids.store(),
                ids.disabled(), ids.disabledName(), ADMIN_HASH, "集成测试停用账号-" + ids.suffix());
    }

    private void update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
        }
    }

    /** 登录名带小写后缀，配合 toUpperCase 断言大小写敏感。 */
    private record TestIds(
            long base,
            String suffix,
            long store,
            long admin,
            long user,
            long disabled) {

        String adminName() {
            return "it-admin-" + suffix;
        }

        String userName() {
            return "it-user-" + suffix;
        }

        String disabledName() {
            return "it-disabled-" + suffix;
        }

        static TestIds create() {
            long base = 8_000_000_000_000_000_000L
                    + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000L) * 10_000L;
            String suffix = Long.toUnsignedString(base, 36);
            return new TestIds(base, suffix, base + 1, base + 10, base + 11, base + 12);
        }
    }
}
