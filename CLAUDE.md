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
- **application**：应用用例（`OperatingDataQuery`、`StoreProductQuery`），集中做参数校验、门店校验与编排，只依赖 domain 接口，不依赖 MyBatis/Spring。
- **infrastructure**：`persistence/adapter/` 下 `@Repository` 实现 domain 接口；`persistence/mapper/` 下 `@Mapper` 接口 + `resources/mapper/*.xml` 原生 MyBatis 查询（新链路）；旧链路用 MyBatis Plus（`InventorySnapshotMapper` 等基于注解/Wrapper）。数据库知识只存在于本模块。
- **bootstrap**：唯一组装点。`config/` 下每个功能模块一个显式 `@Configuration(proxyBeanMethods = false)` + `@Bean` 手工装配应用用例（不做组件扫描式自动注入）；`controller/` 只做 HTTP 参数绑定与响应模型转换，`ApiExceptionHandler` 统一把应用异常映射为 Problem Detail（404 门店/商品不存在，400 参数错误与跨门店仓库）。
- **agent**：空壳模块（仅 pom），Agent 对话能力未开发。

**改动准则**：业务规则放 application；新增查询必须把 `storeId` 下传到底；分页列表避免 N+1（参考 `StoreProductQueryMapper.xml` 固定四次查询模式：总数 + 当前页 + 供应商批量 + 销售批量）。

## 领域与数据库契约

- 数据模型 11 表 2 视图，定义于 `database/好物购数据库建表.sql`（非 git 仓库文档中引用的名字）。
- `store_product_inventory` 主键 `(store_id, product_id)`，仓库外键 `(warehouse_id, store_id)`——数据库层阻止库存关系引用其他门店仓库。
- 商品资料（product）是全局共享的；库存数量、仓库位置是门店级的。
- 视图 `v_product_inventory_query` 含 `store_id`；`v_posted_daily_product_sales` 已按 `batch_id + store_id` 关联有效批次并在库层排除非 `POSTED` 批次——销售区间聚合直接查该视图即可。
- 日期指标口径：未传日期范围时指标为 null（不假定全历史）；传了日期则统计该门店有效批次在闭区间内的销量/销售额/毛利额。
- 旧链路（`store_daily_sales`、`inventory_snapshot` 表）在新版脚本中不存在，属已知遗留，只记录不擅自重构。

## 配置与凭据

- `haowugou-bootstrap/src/main/resources/application-local.yml` **已被 .gitignore 忽略**（含本地数据库密码与 DashScope Key），改它不影响仓库；模板是 `application-local.yml.example`。
- 提交前不要把真实凭据写进会被跟踪的文件。
- 远程为 GitHub（SSH 已配置，`git push` 直接可用）。

## 测试约定

- 应用层：纯 JUnit 单元测试，内存 Repository 替身。
- Controller：standalone MockMvc + 真实应用用例 + 内存替身（只替换 I/O），用 `JsonCompareMode.STRICT` 锁 JSON 契约；不需要 Spring 上下文与 MySQL。
- 基础设施：真实 MySQL 集成测试（见上）。
- 现有规模：应用层 10 + 集成 6 + 启动模块 20 = 36 个测试。