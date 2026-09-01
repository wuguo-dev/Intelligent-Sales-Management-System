package com.haowugou.controller.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.haowugou.application.user.UserAccountQuery;
import com.haowugou.controller.ApiExceptionHandler;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import com.haowugou.domain.user.AppUser;
import com.haowugou.domain.user.UserRepository;
import com.haowugou.domain.user.UserRole;
import com.haowugou.security.AppUserDetailsService;
import com.haowugou.security.AppUserPrincipal;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 从 HTTP 接口验证认证 Controller 的公开契约。
 *
 * <p>用真实认证管理器、真实密码编码器与真实应用用例，只把仓库换成内存替身：
 * 密码校验与会话写入都是纯内存操作，没必要为此拉起 Spring 上下文或 MySQL。
 *
 * <p>登出不在这里测：它由过滤器链而不是 Controller 处理，见 {@code SecurityRulesTest}。
 */
class AuthControllerTest {

    private static final long STORE_ID = 7L;
    private static final long ADMIN_ID = 1L;
    private static final long USER_ID = 2L;
    private static final String ADMIN_PASSWORD = "Admin@123";
    private static final String USER_PASSWORD = "Store1@123";

    private MockMvc mockMvc;
    private RecordingUserRepository users;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
        users = new RecordingUserRepository(
                admin(passwordEncoder.encode(ADMIN_PASSWORD)),
                normalUser(passwordEncoder.encode(USER_PASSWORD)));
        UserAccountQuery userAccountQuery = new UserAccountQuery(users, storeRepository());

        DaoAuthenticationProvider provider =
                new DaoAuthenticationProvider(new AppUserDetailsService(userAccountQuery));
        provider.setPasswordEncoder(passwordEncoder);

        AuthController controller = new AuthController(
                new ProviderManager(provider),
                userAccountQuery,
                new HttpSessionSecurityContextRepository(),
                new ChangeSessionIdAuthenticationStrategy());

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JsonMapper.builder().build()))
                // standalone 模式没有安全过滤器链，@AuthenticationPrincipal 的解析器
                // 得手工注册，否则参数解析不出来直接是 null。
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void loginExposesStableJsonContractForAdmin() throws Exception {
        mockMvc.perform(login("admin", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "userId":1,
                          "username":"admin",
                          "displayName":"系统管理员",
                          "roleId":1,
                          "role":"ADMIN",
                          "store":null,
                          "canManage":true,
                          "canViewCostAndProfit":true
                        }
                        """, JsonCompareMode.STRICT));
    }

    /** 普通用户的响应带上绑定门店，前端据此知道该查哪个门店而不必让用户选。 */
    @Test
    void loginExposesBoundStoreForNormalUser() throws Exception {
        mockMvc.perform(login("store1user", USER_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "userId":2,
                          "username":"store1user",
                          "displayName":"城南店店员",
                          "roleId":2,
                          "role":"USER",
                          "store":{"id":7,"storeCode":"S-007","storeName":"城南店"},
                          "canManage":false,
                          "canViewCostAndProfit":false
                        }
                        """, JsonCompareMode.STRICT));
    }

    /** 响应里不能出现密码哈希：哈希外泄等于把离线爆破的材料递出去。 */
    @Test
    void loginResponseNeverLeaksPasswordHash() throws Exception {
        String body = mockMvc.perform(login("admin", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(body.contains("\"username\":\"admin\""), body);
        assertTrue(!body.contains("passwordHash") && !body.contains("$2a$"), "响应泄漏了密码哈希: " + body);
    }

    @Test
    void loginRejectsWrongPassword() throws Exception {
        mockMvc.perform(login("admin", "wrong-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"status":401,"title":"登录失败","detail":"账号或密码错误"}
                        """));
    }

    /** 账号不存在与密码错误必须回同一句话，否则接口可被用来枚举账号。 */
    @Test
    void loginGivesSameAnswerForUnknownAccount() throws Exception {
        String unknown = mockMvc.perform(login("no-such-user", ADMIN_PASSWORD))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String wrongPassword = mockMvc.perform(login("admin", "wrong-password"))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertEquals(wrongPassword, unknown);
    }

    @Test
    void loginRejectsDisabledAccount() throws Exception {
        users.disable("admin");

        mockMvc.perform(login("admin", ADMIN_PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    /** 登录名区分大小写，与 {@code utf8mb4_bin} 列排序规则一致。 */
    @Test
    void loginKeepsUsernameCaseSensitive() throws Exception {
        mockMvc.perform(login("ADMIN", ADMIN_PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    /** 空账号密码走认证流程失败即 401，不做 400 参数校验——否则暴露「哪些输入算合法」。 */
    @Test
    void loginRejectsBlankCredentialsAsUnauthorized() throws Exception {
        mockMvc.perform(login("", ""))
                .andExpect(status().isUnauthorized());
    }

    /** 认证态必须落进会话，否则登录成功后的下一个请求又是未登录。 */
    @Test
    void loginStoresAuthenticationInSession() throws Exception {
        MvcResult result = mockMvc.perform(login("store1user", USER_PASSWORD))
                .andExpect(status().isOk())
                .andReturn();

        HttpSession session = result.getRequest().getSession(false);
        assertNotNull(session, "登录没有创建会话");
        SecurityContext stored = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertNotNull(stored, "会话里没有认证态");
        AppUserPrincipal principal = (AppUserPrincipal) stored.getAuthentication().getPrincipal();
        assertEquals(USER_ID, principal.userId());
        assertEquals(STORE_ID, principal.storeId());
    }

    /** 防会话固定：登录前就存在的会话，登录后必须换成新的 ID。 */
    @Test
    void loginChangesSessionIdToPreventFixation() throws Exception {
        MockHttpSession preLoginSession = new MockHttpSession();
        String beforeId = preLoginSession.getId();

        MvcResult result = mockMvc.perform(login("admin", ADMIN_PASSWORD).session(preLoginSession))
                .andExpect(status().isOk())
                .andReturn();

        String afterId = result.getRequest().getSession(false).getId();
        assertNotEquals(beforeId, afterId, "登录没有更换会话 ID");
    }

    @Test
    void loginRecordsLastLoginTime() throws Exception {
        mockMvc.perform(login("admin", ADMIN_PASSWORD)).andExpect(status().isOk());

        assertEquals(List.of(ADMIN_ID), users.loginTouches);
    }

    /** 登录成功但审计写入失败时不能把 500 抛给用户：登录本身已经成立。 */
    @Test
    void loginSucceedsEvenWhenRecordingLastLoginFails() throws Exception {
        users.failOnTouch = true;

        mockMvc.perform(login("admin", ADMIN_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void currentUserReportsLoggedInIdentity() throws Exception {
        mockMvc.perform(get("/api/auth/me").with(authenticated(users.byId(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "userId":2,
                          "username":"store1user",
                          "roleId":2,
                          "role":"USER",
                          "store":{"id":7,"storeCode":"S-007","storeName":"城南店"},
                          "canManage":false,
                          "canViewCostAndProfit":false
                        }
                        """));
    }

    /** /me 每次重新查库：登录后被停用的账号应立刻反映出来，而不是拿会话里的旧快照。 */
    @Test
    void currentUserRejectsAccountDisabledAfterLogin() throws Exception {
        AppUser loggedIn = users.byId(ADMIN_ID);
        users.disable("admin");

        mockMvc.perform(get("/api/auth/me").with(authenticated(loggedIn)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"status":404,"title":"账号不存在或已停用"}
                        """));
    }

    @Test
    void csrfEndpointReturnsTokenForFrontendToEcho() throws Exception {
        CsrfToken token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "token-value");

        mockMvc.perform(get("/api/auth/csrf").requestAttr(CsrfToken.class.getName(), token))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "token":"token-value",
                          "headerName":"X-XSRF-TOKEN",
                          "parameterName":"_csrf"
                        }
                        """, JsonCompareMode.STRICT));
    }

    private static MockHttpServletRequestBuilder login(String username, String password) {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password));
    }

    /**
     * 把请求标记为已登录。
     *
     * <p>直接写 {@code SecurityContextHolder}，而不是用 spring-security-test 的
     * {@code authentication(...)}：后者只是把认证态存起来等过滤器去加载，
     * 而 standalone MockMvc 没有过滤器链，结果解析器拿到的主体是 null。
     * 由 {@code @AfterEach} 负责清理，避免污染后面的用例。
     */
    private static RequestPostProcessor authenticated(AppUser user) {
        AppUserPrincipal principal = new AppUserPrincipal(user);
        return request -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    principal, principal.getPassword(), principal.getAuthorities()));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }

    private static StoreRepository storeRepository() {
        return new StoreRepository() {
            @Override
            public List<Store> findAllActive() {
                return List.of(new Store(STORE_ID, "S-007", "城南店"));
            }

            @Override
            public boolean existsActiveById(long storeId) {
                return storeId == STORE_ID;
            }
        };
    }

    private static AppUser admin(String passwordHash) {
        return new AppUser(ADMIN_ID, "admin", passwordHash, "系统管理员", UserRole.ADMIN, null, true);
    }

    private static AppUser normalUser(String passwordHash) {
        return new AppUser(USER_ID, "store1user", passwordHash, "城南店店员", UserRole.USER, STORE_ID, true);
    }

    /** 内存账号库替身，记录最后登录时间的写入以便断言。 */
    private static final class RecordingUserRepository implements UserRepository {

        private final List<AppUser> accounts = new ArrayList<>();
        private final List<Long> loginTouches = new ArrayList<>();
        private boolean failOnTouch;

        private RecordingUserRepository(AppUser... seed) {
            accounts.addAll(List.of(seed));
        }

        @Override
        public Optional<AppUser> findActiveByUsername(String username) {
            return accounts.stream()
                    .filter(account -> account.active() && account.username().equals(username))
                    .findFirst();
        }

        @Override
        public Optional<AppUser> findActiveById(long userId) {
            return accounts.stream()
                    .filter(account -> account.active() && account.id() == userId)
                    .findFirst();
        }

        @Override
        public void touchLastLogin(long userId, LocalDateTime loginAt) {
            if (failOnTouch) {
                throw new IllegalStateException("模拟写入最后登录时间失败");
            }
            loginTouches.add(userId);
        }

        private AppUser byId(long userId) {
            return accounts.stream()
                    .filter(account -> account.id() == userId)
                    .findFirst()
                    .orElseThrow();
        }

        private void disable(String username) {
            accounts.replaceAll(account -> account.username().equals(username)
                    ? new AppUser(
                            account.id(),
                            account.username(),
                            account.passwordHash(),
                            account.displayName(),
                            account.role(),
                            account.storeId(),
                            false)
                    : account);
        }
    }
}
