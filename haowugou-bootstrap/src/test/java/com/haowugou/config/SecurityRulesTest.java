package com.haowugou.config;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import com.haowugou.domain.user.AppUser;
import com.haowugou.domain.user.UserRepository;
import com.haowugou.domain.user.UserRole;
import com.haowugou.security.AppUserPrincipal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * 验证过滤器链的授权规则，也就是权限模型真正生效的那一层。
 *
 * <p>被保护的不是某个 Controller 而是 URL 与角色的对应关系，所以这里在链后面挂一个
 * 万能探针 Controller：任何请求只要走到它就回 200，于是**凡不是 200 的都是过滤器链拦的**。
 * 这样既不需要 MySQL，也不受各 Controller 自身实现影响，测的就是规则表本身。
 *
 * <p>用真实的 {@link SecurityConfiguration}，仓库只给空替身：本测试直接注入认证态，
 * 不走登录，所以账号库不会被读到。
 */
@SpringJUnitWebConfig(classes = SecurityRulesTest.TestConfig.class)
class SecurityRulesTest {

    private static final long OWN_STORE = 7L;
    private static final long OTHER_STORE = 8L;

    private MockMvc mockMvc;

    /**
     * 走 {@code webAppContextSetup} 而不是 {@code standaloneSetup}。
     *
     * <p>{@code csrf()} 这类 post-processor 要通过 {@code WebApplicationContext} 找到链上
     * 真正的 {@code CsrfFilter} 才能把令牌换进去；standalone 模式没有可查的上下文，
     * 令牌进不到 {@code CookieCsrfTokenRepository}，写请求会全部 403。
     */
    @BeforeEach
    void setUp(@Autowired WebApplicationContext context) {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ---------- 未登录 ----------

    @Test
    void rejectsAnonymousAccessWithProblemDetail() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/products", OWN_STORE))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"status":401,"title":"未认证","detail":"请先登录"}
                        """));
    }

    /** 登录与取令牌必须对未登录者开放，否则谁都进不来。 */
    @Test
    void allowsAnonymousLoginAndCsrfEndpoints() throws Exception {
        mockMvc.perform(post("/api/auth/login").with(csrf())).andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk());
    }

    @Test
    void requiresAuthenticationForOtherAuthEndpoints() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    // ---------- 普通用户：门店范围 ----------

    @Test
    void allowsNormalUserToReadOwnStore() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/products", OWN_STORE).with(normalUser()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/stores/{storeId}/products/{productId}", OWN_STORE, 1).with(normalUser()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/stores/{storeId}/warehouses", OWN_STORE).with(normalUser()))
                .andExpect(status().isOk());
    }

    /** 越权读别家门店是这个权限模型最核心的一条红线。 */
    @Test
    void deniesNormalUserAccessToOtherStore() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/products", OTHER_STORE).with(normalUser()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"status":403,"title":"权限不足","detail":"当前账号无权访问该资源"}
                        """));
        mockMvc.perform(get("/api/stores/{storeId}/products/{productId}", OTHER_STORE, 1).with(normalUser()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/stores/{storeId}/warehouses", OTHER_STORE).with(normalUser()))
                .andExpect(status().isForbidden());
    }

    /** storeId 不是数字时必须拒绝：解析失败按拒绝处理，不能放过去。 */
    @Test
    void deniesUnparsableStoreId() throws Exception {
        mockMvc.perform(get("/api/stores/not-a-number/products").with(normalUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    void allowsAdminToReadAnyStore() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/products", OWN_STORE).with(admin()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/stores/{storeId}/products", OTHER_STORE).with(admin()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/stores/{storeId}/warehouses", OTHER_STORE).with(admin()))
                .andExpect(status().isOk());
    }

    // ---------- 写操作与批次管理：仅管理员 ----------

    @Test
    void deniesNormalUserFromImportingAndManagingBatches() throws Exception {
        mockMvc.perform(post("/api/stores/{storeId}/inventory/import", OWN_STORE)
                        .with(normalUser()).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/stores/{storeId}/sales/import", OWN_STORE)
                        .with(normalUser()).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/stores/{storeId}/import-batches", OWN_STORE).with(normalUser()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/stores/{storeId}/import-batches/{batchId}/reversal", OWN_STORE, 1)
                        .with(normalUser()).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void allowsAdminToImportAndManageBatches() throws Exception {
        mockMvc.perform(post("/api/stores/{storeId}/inventory/import", OWN_STORE)
                        .with(admin()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/stores/{storeId}/sales/import", OWN_STORE)
                        .with(admin()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/stores/{storeId}/import-batches", OWN_STORE).with(admin()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/stores/{storeId}/import-batches/{batchId}/reversal", OWN_STORE, 1)
                        .with(admin()).with(csrf()))
                .andExpect(status().isOk());
    }

    /**
     * storeId 走查询参数的旧链路接口只对管理员开放。
     *
     * <p>路径上没有模板变量，门店范围在过滤器层收不住（普通用户可以随便传 storeId），
     * 所以整段限制为管理员专用。
     */
    @Test
    void deniesNormalUserFromQueryParameterScopedEndpoints() throws Exception {
        mockMvc.perform(get("/api/stores").with(normalUser())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/sales/daily").with(normalUser())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inventory").with(normalUser())).andExpect(status().isForbidden());
    }

    @Test
    void allowsAdminOnQueryParameterScopedEndpoints() throws Exception {
        mockMvc.perform(get("/api/stores").with(admin())).andExpect(status().isOk());
        mockMvc.perform(get("/api/sales/daily").with(admin())).andExpect(status().isOk());
        mockMvc.perform(get("/api/inventory").with(admin())).andExpect(status().isOk());
    }

    // ---------- 默认拒绝 ----------

    /** 漏配规则的新接口必须不可访问，连管理员也一样——默认拒绝比默认放开安全。 */
    @Test
    void deniesUnmappedEndpointsEvenForAdmin() throws Exception {
        mockMvc.perform(get("/api/something-new").with(admin())).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/env").with(admin())).andExpect(status().isForbidden());
    }

    // ---------- CSRF ----------

    /** 没有 CSRF 令牌的写请求必须被拒：会话认证靠 Cookie 自动携带，否则第三方站点能借登录态发请求。 */
    @Test
    void rejectsStateChangingRequestWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/api/stores/{storeId}/sales/import", OWN_STORE).with(admin()))
                .andExpect(status().isForbidden());
    }

    @Test
    void allowsLoginWithoutAuthenticationButStillRequiresCsrf() throws Exception {
        mockMvc.perform(post("/api/auth/login")).andExpect(status().isForbidden());
    }

    // ---------- 登出 ----------

    /** 登出由过滤器链处理：回 204 并让会话失效，之后旧会话不能再用。 */
    @Test
    void logoutClearsSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                securityContextFor(normalUserAccount()));

        MvcResult result = mockMvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent())
                .andReturn();

        assertNull(result.getRequest().getSession(false), "登出后会话没有失效");
    }

    // ---------- 夹具 ----------

    private static RequestPostProcessor admin() {
        return authenticated(new AppUser(1L, "admin", "hash", "系统管理员", UserRole.ADMIN, null, true));
    }

    private static RequestPostProcessor normalUser() {
        return authenticated(normalUserAccount());
    }

    private static AppUser normalUserAccount() {
        return new AppUser(2L, "store1user", "hash", "城南店店员", UserRole.USER, OWN_STORE, true);
    }

    /** 本测试有真实过滤器链，可以用 spring-security-test 的标准做法注入认证态。 */
    private static RequestPostProcessor authenticated(AppUser user) {
        return SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(user));
    }

    private static Authentication authenticationFor(AppUser user) {
        AppUserPrincipal principal = new AppUserPrincipal(user);
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, principal.getPassword(), principal.getAuthorities());
    }

    private static SecurityContext securityContextFor(AppUser user) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticationFor(user));
        return context;
    }

    /**
     * 万能探针：任何走到 Controller 的请求都回 200。
     *
     * <p>它不实现任何业务，只用来区分「过滤器链放过了」与「过滤器链拦下了」。
     */
    @RestController
    public static class ProbeController {

        @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST})
        public String reached() {
            return "reached";
        }
    }

    /**
     * 最小可用的安全上下文。
     *
     * <p>{@code @EnableWebMvc} 不是摆设：带路径模板的 {@code requestMatchers} 会构造
     * {@code MvcRequestMatcher}，它依赖 {@code mvcHandlerMappingIntrospector} Bean
     * （生产环境由 Boot 的 MVC 自动配置提供）。少了它整个链装不起来。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    @Import({SecurityConfiguration.class, UserAccountConfiguration.class})
    static class TestConfig {

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        /** 本测试不走登录流程，账号库不会被读到，给个空实现即可。 */
        @Bean
        UserRepository userRepository() {
            return new UserRepository() {
                @Override
                public Optional<AppUser> findActiveByUsername(String username) {
                    return Optional.empty();
                }

                @Override
                public Optional<AppUser> findActiveById(long userId) {
                    return Optional.empty();
                }

                @Override
                public void touchLastLogin(long userId, LocalDateTime loginAt) {
                }
            };
        }

        @Bean
        StoreRepository storeRepository() {
            return new StoreRepository() {
                @Override
                public List<Store> findAllActive() {
                    return List.of(new Store(OWN_STORE, "S-007", "城南店"));
                }

                @Override
                public boolean existsActiveById(long storeId) {
                    return storeId == OWN_STORE;
                }
            };
        }
    }
}
