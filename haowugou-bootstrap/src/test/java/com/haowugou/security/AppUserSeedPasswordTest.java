package com.haowugou.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 校验迁移脚本里的种子密码哈希与脚本注释里公开的明文一致。
 *
 * <p>与 {@code AppUserSeedIntegrationTest} 的分工：那个测试读库里的行，只有账号已经种下时才验得到
 * 哈希——普通用户 store1user 的 INSERT 是条件插入（库里没有任何启用门店时一行都不插），于是全新库上
 * 它整个跳过，粘错的哈希能一路溜到第一个真人尝试登录才暴露。本测试直接读
 * {@code database/migration/2026-09-01-app-user.sql} 这个源头，不连数据库、不看环境变量，
 * 每次 {@code mvn test} 都跑，才真正兑现脚本注释里「会断言它们与上述明文匹配」的承诺。
 *
 * <p>哈希是从 SQL 文本里抓出来的，而不是在测试里另抄一份常量：抄一份就变成测试自己跟自己对，
 * 迁移脚本被改坏照样是绿的。
 *
 * <p>明文口令写死在这里是有意的：它们是脚本注释里公开的本地开发口令，不是任何环境的真实凭据，
 * 部署前必须替换（脚本注释已写明）。
 */
class AppUserSeedPasswordTest {

    private static final String SEED_MIGRATION = "database/migration/2026-09-01-app-user.sql";

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Admin@123";
    private static final String NORMAL_USERNAME = "store1user";
    private static final String NORMAL_PASSWORD = "Store1@123";

    /** BCrypt 哈希：{@code $2<变体>$<两位 cost>$} 后跟 53 位 base64 变体字符，总长 60。 */
    private static final Pattern BCRYPT_HASH =
            Pattern.compile("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}");

    private String migrationSql;
    private BCryptPasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        migrationSql = readMigration();
        // 与生产 SecurityConfiguration 同强度：种子哈希就是按 strength 10 生成的。
        passwordEncoder = new BCryptPasswordEncoder(10);
    }

    @Test
    void documentedAdminPasswordMatchesSeedHash() {
        assertDocumentedPassword(ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    /** 这条是全新库上唯一还能验到 store1user 哈希的断言：它的行可能压根没被插进去。 */
    @Test
    void documentedNormalUserPasswordMatchesSeedHash() {
        assertDocumentedPassword(NORMAL_USERNAME, NORMAL_PASSWORD);
    }

    /** 两个账号的哈希必须不同：粘成同一个会让两个账号共用一套口令，而登录仍然「正常」。 */
    @Test
    void seedHashesAreDistinct() {
        assertNotEquals(seedHashFor(ADMIN_USERNAME), seedHashFor(NORMAL_USERNAME),
                "两个种子账号的密码哈希相同，说明有一处粘错了");
    }

    /** 强度与长度跑偏说明哈希被截断或换了算法，登录会全线失败。 */
    @Test
    void seedHashesUseBcryptStrengthTen() {
        for (String username : new String[] {ADMIN_USERNAME, NORMAL_USERNAME}) {
            String hash = seedHashFor(username);

            assertTrue(hash.startsWith("$2a$10$"),
                    username + " 的密码哈希不是 BCrypt strength 10：" + hash);
            assertEquals(60, hash.length(), username + " 的 BCrypt 哈希长度异常，可能被截断");
        }
    }

    private void assertDocumentedPassword(String username, String plaintext) {
        // 先确认注释里公开的还是这个明文：只改注释不改哈希（或反过来）同样会让人登不进去。
        Pattern documented = Pattern.compile(
                Pattern.quote(username) + "\\s*/\\s*" + Pattern.quote(plaintext));
        assertTrue(documented.matcher(migrationSql).find(),
                SEED_MIGRATION + " 的注释里没有「" + username + " / " + plaintext
                        + "」，测试与脚本记录的口令已经不一致");

        assertTrue(passwordEncoder.matches(plaintext, seedHashFor(username)),
                username + " 的种子哈希与 " + SEED_MIGRATION + " 注释里的明文 " + plaintext
                        + " 不匹配，该账号谁都登不进去");
    }

    /** 取 SQL 里该账号名字面量之后的第一个 BCrypt 哈希，也就是这一条 INSERT 用的哈希。 */
    private String seedHashFor(String username) {
        int usernameAt = migrationSql.indexOf("'" + username + "'");
        assertTrue(usernameAt >= 0,
                SEED_MIGRATION + " 里找不到账号 " + username + " 的 INSERT");

        Matcher matcher = BCRYPT_HASH.matcher(migrationSql);
        assertTrue(matcher.find(usernameAt),
                SEED_MIGRATION + " 里账号 " + username + " 之后没有 BCrypt 哈希字面量");
        return matcher.group();
    }

    /**
     * 从工作目录逐级向上找迁移脚本。
     *
     * <p>surefire 的工作目录是模块目录（{@code haowugou-bootstrap}），而脚本在仓库根下，
     * 向上找比写死 {@code ../} 更耐得住从仓库根跑测试。找不到直接失败而不是跳过：
     * 该脚本在 git 里，缺了是真缺陷，不是环境未就绪。
     */
    private static String readMigration() {
        Path start = Path.of("").toAbsolutePath();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            Path migration = candidate.resolve(SEED_MIGRATION);
            if (Files.isRegularFile(migration)) {
                try {
                    return Files.readString(migration, StandardCharsets.UTF_8);
                } catch (IOException exception) {
                    throw new UncheckedIOException("读取 " + migration + " 失败", exception);
                }
            }
        }
        throw new IllegalStateException("从 " + start + " 逐级向上都找不到 " + SEED_MIGRATION);
    }
}
