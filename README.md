# 好物购（haowugou）—— 百货商场智能经营分析系统

基于 Spring Boot 的 Maven 多模块后端项目。当前已完成经营数据查询的第一条纵向链路，
后续将在同一应用层查询模块之上接入 Agent Tool 和经营分析能力。

## 当前进度

已完成：

- 门店、门店日销售、库存快照领域模型与 Repository 接口；
- MyBatis Plus 持久化 Adapter 和库存商品联表查询；
- 经营数据应用查询模块，集中处理门店校验与查询编排；
- 门店列表、门店日销售、库存快照三个 REST 查询接口；
- 统一的参数错误和门店不可用 Problem Detail 响应；
- 3 个应用层测试和 7 个 Controller 契约测试。

尚未实现：Agent 对话、模型调用、经营分析、库存预警、数据导入、权限与前端页面。

## 模块结构

```text
haowugou/
├── haowugou-common/          公共类型、常量和工具
├── haowugou-domain/          领域对象与 Repository 接口
├── haowugou-application/     业务用例与查询编排
├── haowugou-infrastructure/  MyBatis、数据库和外部系统 Adapter
├── haowugou-agent/           Agent Tool、提示词和对话能力（待开发）
└── haowugou-bootstrap/       Spring Boot 启动、REST 接口与模块组装
```

依赖方向保持单向：

```text
bootstrap → {agent, infrastructure, application}
agent → application → domain → common
infrastructure → domain → common
```

Controller 只处理 HTTP 参数和响应；业务规则集中在 `OperatingDataQuery`；数据库知识仅存在于
infrastructure。未来新增 REST 接口或 Agent Tool 时，可以复用应用层查询模块，而不直接依赖 MyBatis。

## 已实现接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/stores` | 查询全部已启用门店 |
| `GET` | `/api/sales/daily?storeId=1&date=2026-08-23` | 查询门店日销售 |
| `GET` | `/api/inventory?storeId=1&date=2026-08-23` | 查询门店库存快照 |

日期参数统一使用 `yyyy-MM-dd`。门店不存在或未启用时返回 HTTP 404；参数不符合应用约束时返回
HTTP 400。

## 本地开发

要求：Java 21、Maven 3.9+、MySQL 8。

运行前通过 IDEA Run Configuration 或操作系统环境变量提供：

```text
HAOWUGOU_DB_PASSWORD=<本地 MySQL 密码>
```

可选配置：

```text
HAOWUGOU_DB_URL=<JDBC 地址>
HAOWUGOU_DB_USERNAME=<数据库用户名>
DASHSCOPE_API_KEY=<调用模型时使用的 Key>
```

开发阶段直接在 IDEA 运行 `HaowugouApplication`，不需要执行打包。运行测试：

```bash
mvn test
```

## 主要版本

- Spring Boot 3.5.8
- Spring AI 1.1.2
- Spring AI Alibaba 1.1.2.2
- MyBatis Plus 3.5.7
- EasyExcel 4.0.3
- XXL-Job 2.4.2

## 开发记录

阶段性变更记录见 [CHANGELOG.md](CHANGELOG.md)。
