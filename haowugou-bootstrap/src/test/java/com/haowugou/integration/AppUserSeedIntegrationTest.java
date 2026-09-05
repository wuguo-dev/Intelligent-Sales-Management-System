package com.haowugou.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haowugou.domain.user.AppUser;
import com.haowugou.domain.user.UserRole;
import com.haowugou.infrastructure.persistence.user.AppUserMapper;
import com.haowugou.infrastructure.persistence.user.MybatisUserRepository;
import java.io.InputStream;
import java.util.Optional;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 真实 MySQL 集成测试：验证迁移脚本种下的账号真的落进了库，并且能按真实 Mapper 读出来。
 *
 * <p>与 {@link com.haowugou.config.AppUserSeedPasswordTest} 的分工：那个测试读迁移脚本源文件，
 * 无条件校验哈希字面量与注释明文一致；本测试校验的是<b>库里的行</b>——迁移是否真的执行过、
 * 行是否能通过 {@link MybatisUserRepository} 与 {@code AppUserMapper.xml} 读出来、
 * 角色与门店绑定是否符合权限口径。哈希对不上还会在这里再兜一次，
 * 因为库里的行可能来自旧版脚本或被人手工改过，那是脚本自身查不出的失败。
 *
 * <p>与其他集成测试不同，本测试<b>不建夹具</b>，读的就是
 * {@code database/migration/2026-09-01-app-user.sql} 种下的生产行，所以只读、不写、不回滚。
 * 库里没有这些账号时按跳过处理而不是失败：那说明环境未就绪（迁移没执行，或普通用户那条
 * 条件 INSERT 因为库里没有启用门店而一行都没插），不是代码缺陷。
 *
 * <p>明文口令写在断言里是有意的：它们是迁移脚本注释里公开的本地开发口令，
 * 不是任何环境的真实凭据，部署前必须替换（脚本注释已写明）。
 */
class AppUserSeedIntegrationTest {

    private static final String SEED_MIGRATION = "database/migration/2026-09-01-app-user.sql";

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Admin@123";
    private static final String NORMAL_USERNAME = "store1user";
    private static final String NORMAL_PASSWORD = "Store1@123";

    private SqlSession session;
    private MybatisUserRepository users;
    private BCryptPasswordEncoder passwordEncoder;

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
        users = new MybatisUserRepository(session.getMapper(AppUserMapper.class));
        // 与生产 SecurityConfiguration 同强度：种子哈希就是按 strength 10 生成的。
        passwordEncoder = new BCryptPasswordEncoder(10);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            try {
                session.rollback();
            } catch (RuntimeException exception) {
                throw new IllegalStateException("回滚 MySQL 集成测试会话失败", exception);
            } finally {
                session.close();
            }
        }
    }

    @Test
    void seededAdminCanAuthenticateWithDocumentedPassword() {
        AppUser admin = adminAccount();

        assertTrue(passwordEncoder.matches(ADMIN_PASSWORD, admin.passwordHash()),
                "种子管理员的密码哈希与 " + SEED_MIGRATION + " 注释里的明文不匹配，登录会全线失败");
        assertEquals(UserRole.ADMIN, admin.role());
        // 管理员不绑门店：绑了就等于被降级成只能看一家店。
        assertNull(admin.storeId());
        assertTrue(admin.active());
    }

    @Test
    void seededNormalUserCanAuthenticateAndIsBoundToOneStore() {
        AppUser user = normalAccount();

        assertTrue(passwordEncoder.matches(NORMAL_PASSWORD, user.passwordHash()),
                "种子普通用户的密码哈希与 " + SEED_MIGRATION + " 注释里的明文不匹配，登录会全线失败");
        assertEquals(UserRole.USER, user.role());
        // 普通用户必须绑门店，否则门店过滤会静默失效（AppUser 与 CHECK 约束两侧都拦）。
        assertNotNull(user.storeId());
        assertTrue(user.storeId() > 0);
        assertTrue(user.active());
    }

    /** 两个种子账号的角色必须不同，否则等于没有权限分级。 */
    @Test
    void seededAccountsCoverBothRoles() {
        AppUser admin = adminAccount();
        AppUser user = normalAccount();

        assertTrue(admin.role().canManage());
        assertFalse(user.role().canManage());
        assertTrue(admin.role().canViewCostAndProfit());
        assertFalse(user.role().canViewCostAndProfit());
    }

    /**
     * 管理员的密码只以 BCrypt 哈希落库。
     *
     * <p>脚本源文件那侧已经查过字面量，这里查的是<b>列里实际存着什么</b>：
     * 有人手工把明文写进 {@code password_hash}，或列宽不够把 60 位哈希截断，
     * 都只有读库才看得见。两个账号分成两条用例，是为了让 store1user 缺失时
     * 不把管理员这半边也一起跳过。
     */
    @Test
    void seededAdminPasswordIsStoredAsBcryptHash() {
        assertBcryptHash(ADMIN_USERNAME, adminAccount().passwordHash());
    }

    /** 普通用户的密码只以 BCrypt 哈希落库。 */
    @Test
    void seededNormalUserPasswordIsStoredAsBcryptHash() {
        assertBcryptHash(NORMAL_USERNAME, normalAccount().passwordHash());
    }

    private static void assertBcryptHash(String username, String hash) {
        assertTrue(hash.startsWith("$2a$10$"),
                username + " 的密码哈希不是 BCrypt strength 10：" + hash);
        assertEquals(60, hash.length(), username + " 的 BCrypt 哈希长度异常，可能被截断");
    }

    private AppUser adminAccount() {
        return seedAccount(ADMIN_USERNAME, "请先执行 " + SEED_MIGRATION);
    }

    /**
     * 普通用户缺失比管理员缺失多一种原因，所以跳过消息要分开写。
     *
     * <p>它的 INSERT 是 {@code SELECT ... FROM store WHERE is_active = 1 LIMIT 1}：
     * 库里没有任何启用门店时一行都不插（{@code chk_app_user_store_scope} 不允许普通用户
     * {@code store_id} 为 NULL）。空库上跳过是对的，但消息得指向门店而不是让人反复重跑迁移。
     */
    private AppUser normalAccount() {
        return seedAccount(NORMAL_USERNAME,
                "它的 INSERT 依赖库里存在 is_active = 1 的门店；先执行 " + SEED_MIGRATION
                        + "，若仍然没有该账号，说明 store 表里还没有启用门店");
    }

    private AppUser seedAccount(String username, String hint) {
        Optional<AppUser> account = users.findActiveByUsername(username);
        Assumptions.assumeTrue(account.isPresent(),
                "库里没有启用的种子账号 " + username + "：" + hint);
        return account.orElseThrow();
    }

    private void addMapperXml(Configuration configuration, String resource) throws Exception {
        try (InputStream stream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments())
                    .parse();
        }
    }
}
