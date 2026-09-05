# 用户模块设计：管理员 / 普通用户与门店级权限

## 1. 目标与非目标

**目标**：给现有 API 加上登录与授权。账号由开发人员直接写库，按 `role_id` 区分管理员与普通用户；
管理员可用全部功能，普通用户只能查看商品售价、库存数量与所处仓库。

**非目标**（本次不做）：注册、改密、找回密码、用户增删改接口、员工任务模块、
多门店授权表（一个用户对多个门店）、Agent 模块接入鉴权。

## 2. 已确认决策

| 决策点 | 结论 |
| --- | --- |
| 认证机制 | Spring Security + Session Cookie；`POST /api/auth/login` 发 Cookie，BCrypt 存密码 |
| 门店范围 | `app_user.store_id` 绑定单门店；管理员 `store_id IS NULL` 代表全部门店 |
| 字段可见性 | 普通用户只看条码/名称/单位/售价/库存数量/库存状态/仓库，隐藏成本价、毛利、销售指标、供应商 |
| 列表售价 | 给商品列表加 `salePrice`（SQL 已查 `v.sale_price`，只是 domain 模型没带） |

## 3. 补充决策（需要你确认，写在计划里以免静默假设）

1. **旧链路接口设为管理员专用**：`GET /api/stores`、`/api/sales/daily`、`/api/inventory` 用
   `storeId` 查询参数而非嵌套路径，门店范围守卫拦不住（普通用户能传任意 `storeId`）。
   这三个接口本就查新脚本里不存在的遗留表，直接限管理员最安全。普通用户要门店名从
   `/api/auth/me` 拿。
2. **普通用户传日期/供应商参数时静默忽略**，不报 400。理由：前端共用一套查询组件，
   报错会让普通用户页面直接不可用；而普通用户的响应模型本就不含期间指标与供应商，
   照常查询只是白跑一次 SQL。
3. **CSRF 保持开启**（Cookie 认证下关掉就等于把跨站请求伪造敞开）。用
   `CookieCsrfTokenRepository.withHttpOnlyFalse()`，Session Cookie 设 `SameSite=Strict`，
   额外提供 `GET /api/auth/csrf` 让 Postman 拿 token；`POST /api/auth/login` 免 CSRF。
4. **导入/撤销的 `operatorName`、`reversedBy` 暂不改为取自登录用户**。现在这两个值由请求体传入，
   有了登录态后应该服务端填，但那会改动已冻结的请求契约与 5 个测试类，属于独立一次改动。
   本次只在计划里记为后续建议。

## 4. 数据库

新增 `app_user` 表（不叫 `user`，那是 MySQL 系统表名）。

```sql
CREATE TABLE IF NOT EXISTS `app_user` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `username` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '登录名，区分大小写',
    `password_hash` VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，留长以便换算法',
    `display_name` VARCHAR(64) NOT NULL COMMENT '展示名',
    `role_id` TINYINT UNSIGNED NOT NULL COMMENT '1=ADMIN 管理员，2=USER 普通用户',
    `store_id` BIGINT UNSIGNED NULL COMMENT '普通用户绑定门店；管理员为 NULL 代表全部门店',
    `is_active` TINYINT(1) NOT NULL DEFAULT 1,
    `last_login_at` DATETIME(3) NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_user_username` (`username`),
    KEY `idx_app_user_store` (`store_id`),
    CONSTRAINT `fk_app_user_store` FOREIGN KEY (`store_id`) REFERENCES `store` (`id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_app_user_role` CHECK (`role_id` IN (1, 2)),
    CONSTRAINT `chk_app_user_active` CHECK (`is_active` IN (0, 1)),
    CONSTRAINT `chk_app_user_store_scope` CHECK (
        (`role_id` = 1 AND `store_id` IS NULL) OR (`role_id` = 2 AND `store_id` IS NOT NULL))
) ENGINE = InnoDB COMMENT = '系统登录账号';
```

`chk_app_user_store_scope` 把「管理员不绑店、普通用户必须绑店」做成数据库不变量，
和 `import_batch` 用生成列表达业务不变量是同一思路——写库建账号时填错立刻报错，
而不是等到运行期变成一个能看全部门店的"普通用户"。

**落地方式**：
- 新增 `database/migration/2026-09-01-app-user.sql`（建表 + 种子账号，不幂等，与现有迁移一致）
- 同步写入 `database/schema.sql`，让全新库初始化也带这张表

**种子账号**：`admin` / `store1user`，密码明文写在迁移脚本注释里。BCrypt 哈希不能凭空手写，
实现时用一次性 `main` 生成真实哈希再粘进 SQL，并在集成测试里断言
`passwordEncoder.matches(明文, 库里哈希)`，保证脚本里的哈希真的能登进去。

## 5. 分层实现

依赖方向不变：`bootstrap → application → domain`，Spring Security 只出现在 bootstrap。

### domain（`com.haowugou.domain.user`，纯 POJO）
- `UserRole`（enum）：`ADMIN(1)`、`USER(2)`，带 `fromRoleId(int)` 与 `roleId()`；
  权限口径做成领域方法 `canManage()`、`canViewCostAndProfit()`，让"普通用户看不到成本"
  这条业务规则有明确归属，而不是散在 controller 的 `if`
- `AppUser`（record）：`id / username / passwordHash / displayName / role / storeId / active`；
  构造器里校验 `ADMIN ⇒ storeId == null`、`USER ⇒ storeId != null`，与数据库 CHECK 同口径
- `UserRepository`（接口）：`Optional<AppUser> findActiveByUsername(String)`、
  `void touchLastLogin(long userId, LocalDateTime at)`

### application（`com.haowugou.application.user`）
- `UserAccountQuery`：`AppUser loadForAuthentication(String username)`（校验非空、找不到抛
  `UserAccountNotFoundException`）、`UserProfileResult findProfile(long userId)`（拼门店信息给 `/me`）、
  `void recordLogin(long userId)`
- `UserProfileResult`（record）：`AppUser user` + `Store store`（管理员为 null）
- `exception/UserAccountNotFoundException`、`exception/InvalidUserQueryException`
  （放 `exception/` 子包，遵守既有约定）

绑定门店停用时**不拒绝登录**——账号本身有效，能不能看数据由 `StoreProductQuery`
现有的门店校验决定（已有 404 门店不存在或未启用），不在登录处再造一条失败路径。

### infrastructure（`com.haowugou.infrastructure.persistence.user`）
沿用新链路的原生 MyBatis 方式（不用 MyBatis Plus）：
- `AppUserDataObject`（Lombok `@Getter/@Setter` 投影）
- `AppUserMapper` + `resources/mapper/AppUserMapper.xml`：`findActiveByUsername`、`updateLastLoginAt`
- `MybatisUserRepository implements UserRepository`（`@Repository`）

### bootstrap
配置（`config/`）：
- `UserAccountConfiguration`：显式 `@Bean UserAccountQuery`，与其他 5 个配置类同风格
- `SecurityConfiguration`：`PasswordEncoder`（BCrypt）、`UserDetailsService`、
  `DaoAuthenticationProvider`、`AuthenticationManager`、`SecurityFilterChain`、`SecurityContextRepository`

安全组件（`security/`）：
- `AppUserPrincipal implements UserDetails`：额外带 `userId`、`UserRole`、`storeId`，
  controller 与授权管理器都从它取门店范围
- `AppUserDetailsService`：调 `UserAccountQuery`，转 `AppUserPrincipal`；
  用户不存在时抛 `UsernameNotFoundException`，交给 `DaoAuthenticationProvider`
  做防时序攻击的 dummy 校验
- `StoreScopeAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext>`：
  从 URI 模板变量取 `storeId`，`ADMIN` 直接放行，`USER` 仅当等于自己的 `storeId` 才放行。
  **写成具名类而非 lambda**（`AuthorizationManager` 在 6.4+ 有 `check`/`authorize` 两个方法，
  具名类由编译器指明该覆写哪个，不赌函数式接口形状）
- `ProblemDetailAuthenticationEntryPoint`（401）、`ProblemDetailAccessDeniedHandler`（403）：
  让未登录/越权也返回与 `ApiExceptionHandler` 一致的 Problem Detail JSON，
  而不是 Spring Security 默认的空响应体

Controller（`controller/auth/`）：
- `AuthController`：`POST /api/auth/login`（JSON 入参，调 `AuthenticationManager`，
  成功后新建 `SecurityContext` 并显式存进 Session——顺带换 Session ID 防会话固定，
  再记录 `last_login_at`）、`GET /api/auth/me`、`GET /api/auth/csrf`；
  登出走 Spring Security 的 `logout()` + `HttpStatusReturningLogoutSuccessHandler(204)`，
  不自己写（要清 Session、清 Cookie、清 SecurityContext，自己写容易漏）
- `LoginRequest` / `AuthenticatedUserResponse` / `CsrfTokenResponse`

### 字段遮蔽（`controller/product/`）
用**独立响应模型**而不是把敏感字段置 null：置 null 会让 JSON 里仍留着 `taxCostPrice` 键，
等于告诉普通用户"有这个字段只是不给你看"，且和现有 STRICT 断言里"无日期范围时指标为 null"
的语义撞车。

新增 `RestrictedStoreProductItemResponse`、`RestrictedStoreProductPageResponse`、
`RestrictedStoreProductDetailResponse`（含门店摘要 + 最小字段集）。
`StoreProductController` 的两个查询方法改为返回 `ResponseEntity<?>`，
按 `principal.role().canViewCostAndProfit()` 选投影。查询用例与 SQL 完全不变——
普通用户拿到的是同一份数据的窄投影，不是另一条查询链路。

### 改动既有文件
- `StoreProductListItem` 加 `BigDecimal salePrice`
- `MybatisStoreProductQueryRepository.toListItem` 传 `row.getSalePrice()`
- `StoreProductItemResponse` 加 `salePrice`
- `ApiExceptionHandler` 加 `UserAccountNotFoundException`(404) / `InvalidUserQueryException`(400)
- `haowugou-bootstrap/pom.xml` 加 `spring-boot-starter-security`
  与 `spring-security-test`(test)
- `application.yml` 加 `server.servlet.session.cookie.same-site: strict` 与 `http-only: true`

## 6. 权限矩阵

| 端点 | 管理员 | 普通用户 |
| --- | --- | --- |
| `POST /api/auth/login`、`GET /api/auth/csrf` | 公开 | 公开 |
| `GET /api/auth/me`、`POST /api/auth/logout` | ✅ | ✅ |
| `GET /api/stores/{storeId}/products`、`/products/{productId}` | 全字段 | 仅本店 + 最小字段集 |
| `GET /api/stores/{storeId}/warehouses` | ✅ | 仅本店 |
| `POST /api/stores/{storeId}/inventory/import` | ✅ | 403 |
| `POST /api/stores/{storeId}/sales/import` | ✅ | 403 |
| `GET /api/stores/{storeId}/import-batches`(+详情) | ✅ | 403 |
| `POST /api/stores/{storeId}/import-batches/{id}/reverse` | ✅ | 403 |
| `GET /api/stores`、`/api/sales/daily`、`/api/inventory` | ✅ | 403（补充决策 1） |
| 其他一切 | 需登录 | 需登录 |

规则在 `SecurityFilterChain` 里集中表达：先按方法+路径判角色，
再对 `/api/stores/{storeId}/**` 整体挂 `StoreScopeAuthorizationManager`。
**门店范围守卫是在既有 `storeId` 查询条件之上再加一道**，不替代任何一层的 `storeId` 下传
（架构规范 §9）。

## 7. 测试计划

现有 151 个测试全部不受影响：controller 测试用 `standaloneSetup`，不经过 Security 过滤器链；
集成测试用裸 MyBatis + `UnpooledDataSource`，不起 Spring 上下文。

新增：
1. **`UserAccountQueryTest`**（application，内存替身）：找到/找不到/空用户名；
   顺带覆盖 `AppUser` 与 `UserRole` 的不变量（domain 模块没有 test 目录与 JUnit 依赖，
   按现有做法把领域断言放在 application 层）
2. **`AuthControllerTest`**（bootstrap，`standaloneSetup` + 桩 `AuthenticationManager`）：
   登录成功/密码错/`me` 的 JSON 契约，`JsonCompareMode.STRICT`
3. **`SecurityRulesTest`**（bootstrap，`@SpringJUnitWebConfig` 只装 `SecurityConfiguration`
   + 哑 controller + `springSecurity()` 后置处理器）：未登录 401、普通用户调导入 403、
   普通用户跨店 403、普通用户本店 200、管理员跨店 200。
   授权规则是安全边界，必须走真实过滤器链验证，不能只靠单元测试
4. **`StoreProductControllerTest`** 扩充：同一门店同一商品，管理员投影含 `taxCostPrice`、
   普通用户投影不含；并同步更新列表 STRICT 断言里新增的 `salePrice`
5. **`MybatisUserRepositoryIntegrationTest`**（infrastructure，真实 MySQL，
   `Assumptions.assumeTrue` 跳过、高位 ID + `IT-` 前缀、JDBC 回滚）：
   查得到启用账号、查不到停用账号、`touchLastLogin` 落库
6. **`AppUserSeedIntegrationTest`**（bootstrap，真实 MySQL）：迁移脚本里的两个种子账号
   哈希能通过 `BCryptPasswordEncoder.matches` —— 防止 SQL 里粘错哈希导致谁都登不进去
7. `MybatisStoreProductQueryRepositoryIntegrationTest` 补 `salePrice` 断言

验证命令：`HAOWUGOU_DB_PASSWORD=<密码> mvn -pl haowugou-bootstrap -am test`

## 8. 文档同步

- `CLAUDE.md` 加一节「用户与权限切片」：记录 `app_user` 的 CHECK 不变量、
  管理员 `store_id IS NULL` 口径、门店守卫不替代 `storeId` 下传、
  普通用户用独立窄投影而非置 null、旧链路三接口限管理员的理由
- `database/migration/` 新脚本按现有格式写背景、做法、幂等性、影响

## 9. 实施顺序

1. 迁移 SQL + 建表脚本同步，本地建库跑通，生成并回填 BCrypt 哈希
2. domain 三个类型 → `UserAccountQueryTest` 先红后绿
3. infrastructure Mapper/XML/Adapter + 集成测试
4. `SecurityConfiguration` + security 包 + `SecurityRulesTest`
5. `AuthController` + 响应模型 + `AuthControllerTest`
6. `salePrice` 贯通（domain → infrastructure → response → 两处测试断言）
7. 商品接口双投影 + controller 测试扩充
8. 全量 `mvn test`，文档同步