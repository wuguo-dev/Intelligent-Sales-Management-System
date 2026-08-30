# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

好物购：百货商场多门店智能经营分析系统的 Spring Boot 后端。Maven 多模块，Java 21，MySQL 8。
核心业务规则：**所有商品/库存/仓库/销售查询必须按 `storeId` 隔离**；销售指标只统计 `POSTED` 批次。

## 常用命令

```bash
mvn test                                              # 全量测试（注意：MySQL 集成测试无环境变量会跳过）
mvn -pl haowugou-bootstrap -am test                   # 启动模块及全部上游依赖（覆盖所有模块）
mvn -pl haowugou-infrastructure -am test              # 基础设施层（含集成测试）
mvn -pl <模块> -am test -Dtest=StoreProductQueryTest  # 跑单个测试类
mvn -pl haowugou-bootstrap -am package -DskipTests    # 构建可执行 jar
java -jar haowugou-bootstrap/target/haowugou-bootstrap-0.0.1-SNAPSHOT.jar   # 启动（端口 8080，默认 profile=local）
```

**MySQL 集成测试**（`haowugou-infrastructure` 的 `MybatisStoreProductQueryRepositoryIntegrationTest`）：
需要真实 MySQL，连接信息从环境变量读取，未设置时 `Assumptions.assumeTrue` 自动跳过（不算失败）：

```bash
HAOWUGOU_DB_PASSWORD=<密码> mvn -pl haowugou-bootstrap -am test
# 可选：HAOWUGOU_DB_URL / HAOWUGOU_DB_USERNAME（默认 127.0.0.1:3306/haowugou, root）
```

集成测试用高位 ID（`8e18+`）与 `IT-` 前缀门店编码构造夹具，直接回滚 JDBC Connection，结束后不留测试数据。

## 架构

依赖方向单向：`bootstrap → {agent, infrastructure, application} → domain → common`。

- **domain**：领域模型 + Repository 接口（如 `StoreRepository`、`StoreProductQueryRepository`），不依赖任何框架。
- **application**：应用用例（`OperatingDataQuery`、`StoreProductQuery`），集中做参数校验、门店校验与编排，只依赖 domain 接口，不依赖 MyBatis/Spring。每个功能包下的应用异常统一收进 `<功能包>/exception/` 子包（如 `application.product.exception.StoreProductNotFoundException`），包根目录只留用例与结果模型。
- **infrastructure**：`persistence/adapter/` 下 `@Repository` 实现 domain 接口；`persistence/mapper/` 下 `@Mapper` 接口 + `resources/mapper/*.xml` 原生 MyBatis 查询（新链路）；旧链路用 MyBatis Plus（`InventorySnapshotMapper` 等基于注解/Wrapper）。数据库知识只存在于本模块。
- **bootstrap**：唯一组装点。`config/` 下每个功能模块一个显式 `@Configuration(proxyBeanMethods = false)` + `@Bean` 手工装配应用用例（不做组件扫描式自动注入）；`controller/` 只做 HTTP 参数绑定与响应模型转换，`ApiExceptionHandler` 统一把应用异常映射为 Problem Detail（404 门店/商品不存在，400 参数错误与跨门店仓库）。
- **agent**：空壳模块（仅 pom），Agent 对话能力未开发。

**改动准则**：业务规则放 application；新增查询必须把 `storeId` 下传到底；分页列表避免 N+1（参考 `StoreProductQueryMapper.xml` 固定四次查询模式：总数 + 当前页 + 供应商批量 + 销售批量）；新增应用异常一律放进对应功能包的 `exception/` 子包，别落在包根。

**导入切片**：两条导入链路各自独立（`domain.importbatch` = 初始库存、`domain.salesimport` = 每日销售），
端口、Mapper XML 与解析器都不共用——行模型和写入表不同，且给同一端口加第二个实现会让 `config/` 里
按类型注入的 `@Bean` 变成歧义注入。共同模式：上传即同步校验过账（方案 A，全有或全无），
SHA-256 内容查重，EasyExcel 按表头名而非列序定位，整行文本写入 `import_raw_row.raw_data` 留审计，
库存流水余额先 `SELECT` 现有值再在 Java 侧计算以满足 `chk_inventory_movement_balance`。
两者的行级错误策略相反：初始库存遇未知条码整批 FAILED，每日销售则建 `PENDING` 商品后照常入账
（销售事实不能因主数据缺失而丢）。

## 领域与数据库契约

- 数据模型 11 表 2 视图，定义于 `database/好物购数据库建表.sql`（非 git 仓库文档中引用的名字）。
- `store_product_inventory` 主键 `(store_id, product_id)`，仓库外键 `(warehouse_id, store_id)`——数据库层阻止库存关系引用其他门店仓库。
- 商品资料（product）是全局共享的；库存数量、仓库位置是门店级的。
- 视图 `v_product_inventory_query` 含 `store_id`；`v_posted_daily_product_sales` 已按 `batch_id + store_id` 关联有效批次并在库层排除非 `POSTED` 批次——销售区间聚合直接查该视图即可。
- 日期指标口径：未传日期范围时指标为 null（不假定全历史）；传了日期则统计该门店有效批次在闭区间内的销量/销售额/毛利额。
- 旧链路（`store_daily_sales`、`inventory_snapshot` 表）在新版脚本中不存在，属已知遗留，只记录不擅自重构。
- `daily_product_sales` 唯一键按 `supplier_key = IFNULL(supplier_id, 0)`：同批次同商品的多个**未识别**供应商只能落一条记录，写入前必须在应用层按该口径归并（数量与收入求和，`reported_gross_profit_rate` 记 NULL）。
- `import_batch` 用生成列 + 唯一键表达业务不变量：`active_sales_date`（每店每业务日期只能有一个有效销售批次）、`active_initial_inventory`（每店只能有一个有效初始库存批次）、`file_hash`（同店同类型同内容只能导一次）。失败批次的 `file_hash` 同样占键，改内容后哈希变化即可重导。
- `inventory_movement` 有 `quantity_change <> 0` 与 `balance_after = balance_before + quantity_change` 两条 CHECK：净销量为 0 的商品不能写流水。
- SQL 细节：`import_raw_row.row_number` 是 MySQL 8 保留字，必须反引号；库存 upsert 用 8.0.19+ 行别名 `AS new`，右侧同名列要表名限定。

## 配置与凭据

- `haowugou-bootstrap/src/main/resources/application-local.yml` **已被 .gitignore 忽略**（含本地数据库密码与 DashScope Key），改它不影响仓库；模板是 `application-local.yml.example`。
- 提交前不要把真实凭据写进会被跟踪的文件。
- 远程为 GitHub（SSH 已配置，`git push` 直接可用）。

## 测试约定

- 应用层：纯 JUnit 单元测试，内存 Repository 替身。
- Controller：standalone MockMvc + 真实应用用例 + 内存替身（只替换 I/O），用 `JsonCompareMode.STRICT` 锁 JSON 契约；不需要 Spring 上下文与 MySQL。
- 基础设施：真实 MySQL 集成测试（见上）。
- 导入链路的全链路集成测试放在 `haowugou-bootstrap` 的 `integration` 包：bootstrap 是唯一同时可见
  application 与 infrastructure 的模块，放这里才不用给 infrastructure 加对 application 的测试依赖。
- 真实 POS 业务文件不进仓库：`RealPosDailySalesFileImportTest` 从环境变量
  `HAOWUGOU_POS_SALES_FILE` 取路径，未设置或文件不存在时跳过。
- 单跑一个测试类要加 `-Dsurefire.failIfNoSpecifiedTests=false`（上游模块没有该类会报
  No tests matching pattern，`-DfailIfNoTests=false` 无效）。
- 现有规模：应用层 41 + 基础设施 22（含 6 个真实 MySQL 集成）+ 启动模块 56（含 12 个真实 MySQL
  全链路集成与 1 个真实 POS 文件端到端）= 119 个测试。