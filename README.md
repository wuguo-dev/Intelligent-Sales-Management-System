# 好物购（haowugou）—— 百货商场智能经营分析系统

基于 Spring Boot 的 Maven 多模块后端项目。当前已完成经营数据查询的第一条纵向链路，
后续将在同一应用层查询模块之上接入 Agent Tool 和经营分析能力。

## 当前进度

已完成：

- 门店、门店日销售、库存快照领域模型与 Repository 接口；
- MyBatis Plus 持久化 Adapter 和库存商品联表查询；
- 经营数据应用查询模块，集中处理门店校验与查询编排；
- 门店列表、门店日销售、库存快照三个 REST 查询接口；
- 门店范围商品分页、商品详情、启用仓库三个 REST 查询接口，支持多维度筛选与期间销售指标；
- 初始库存导入纵向切片：POS 商品资料（.xls / .xlsx）上传 → 文件校验 → 行级校验 → 单事务过账，
  批次与原始行留审计、库存累加、写库存流水；可选仓库分配（不传则待分配，后续编辑页面指定）；
- 每日销售导入与库存扣减纵向切片：POS 商品销售汇总（.xls / .xlsx）按业务日期上传 → 单事务写销售事实
  与库存扣减流水（SALE_OUT / 退货 SALE_RETURN），未知条码自动建待完善商品后仍照常入账；
- 导入批次查询与撤销纵向切片：批次分页列表（按类型/状态/数据日期筛选）、批次详情与问题行分页、
  撤销已入账批次（翻 REVERSED、按原流水写反向流水、回滚库存），撤销后同一份文件可原样重传；
- 统一的参数错误和门店不可用 Problem Detail 响应；
- 151 个测试：应用层 54、基础设施 22（含 6 个真实 MySQL 集成）、启动模块 75
  （含 35 个导入与批次契约测试、19 个真实 MySQL 全链路集成与 1 个真实 POS 文件端到端）。

尚未实现：Agent 对话、模型调用、经营分析、库存预警、商品资料导入与商品编辑写接口、
仓库批量分配、权限与前端页面。

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
| `GET` | `/api/stores/{storeId}/products` | 查询指定门店的商品分页列表 |
| `GET` | `/api/stores/{storeId}/products/{productId}` | 查询指定门店内单个商品详情 |
| `GET` | `/api/stores/{storeId}/warehouses` | 查询指定门店的已启用仓库 |
| `POST` | `/api/stores/{storeId}/inventory/import` | 上传 POS 商品资料 Excel 导入初始库存 |
| `POST` | `/api/stores/{storeId}/sales/import?businessDate=2026-08-29` | 上传 POS 商品销售汇总 Excel 导入日销售并扣减库存 |
| `GET` | `/api/stores/{storeId}/import-batches` | 查询指定门店的导入批次分页列表 |
| `GET` | `/api/stores/{storeId}/import-batches/{batchId}` | 查询单个批次详情与问题行分页 |
| `POST` | `/api/stores/{storeId}/import-batches/{batchId}/reverse` | 撤销已入账批次并回滚库存 |

日期参数统一使用 `yyyy-MM-dd`。门店不存在或未启用时返回 HTTP 404；参数不符合应用约束时返回
HTTP 400。门店商品分页接口支持条码/名称关键字、品类、供应商、仓库、库存状态、库存范围、商品
资料状态、日期范围（统计该门店有效销售批次的期间销量/销售额/毛利额）与分页参数；未提供日期
范围时期间销售指标为 `null`。

初始库存导入使用 `multipart/form-data` 上传 `file` 字段（.xls / .xlsx，上限 5MB），可选查询参数
`warehouseId`（仓库必须属于该门店；不传则库存行仓库待分配）。响应 200 时批次为终态：`status=POSTED`
表示全量过账；`status=FAILED` 表示存在行级错误（未知条码/负数量/重复条码等），整批不入账并返回
行级错误明细。文件级错误返回 400；同店同文件重复上传或该店已有有效初始库存批次返回 409。

每日销售导入同样用 `multipart/form-data` 上传 `file` 字段，并要求查询参数 `businessDate`
（`yyyy-MM-dd`，不能晚于今天；POS 文件本身无日期列）。响应 200 时批次为终态，摘要含 `salesRows`
（落库销售事实数）、`pendingProductsCreated`（未知条码新建的待完善商品数）与 `deductedProducts`
（产生库存流水的商品数）。销售毛利额按 `销售收入 × POS 毛利率 ÷ 100` 计算；数量与收入同时为 0 的行
只留原始行审计，不写销售事实也不产生流水；净销量为负记为退货并把库存加回。同店同文件重复上传或
该店该业务日期已有有效销售批次返回 409。

## 本地开发

要求：Java 21、Maven 3.9+、MySQL 8。

首次克隆后，将 `haowugou-bootstrap/src/main/resources/application-local.yml.example`
复制为同目录的 `application-local.yml` 并填写本地 MySQL 密码与 DashScope API Key
（该文件已被 `.gitignore` 忽略，不会进入 Git 历史）。也可以通过环境变量覆盖，运行前
通过 IDEA Run Configuration 或操作系统环境变量提供：

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
