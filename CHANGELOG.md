# 变更记录

本文件记录项目开发阶段的重要功能、设计调整与验证结果。

## [Unreleased]

### 新增

- 建立门店、门店日销售、库存快照领域模型与 Repository 接口；
- 实现 MyBatis Plus 持久化 Adapter；
- 实现 `OperatingDataQuery` 经营数据查询模块；
- 新增门店列表、门店日销售、库存快照 REST 接口；
- 新增应用层测试与 MockMvc HTTP 契约测试；
- 建立门店商品查询纵向切片：领域查询条件与仓储接口、`StoreProductQuery` 应用用例、
  MyBatis 多门店查询 Adapter（分页固定四次查询，无 N+1）；
- 新增门店商品分页、商品详情、启用仓库三个嵌套路径 REST 接口，支持关键字、品类、供应商、
  仓库、库存状态/范围、资料状态、日期范围筛选与期间销售指标；
- 新增 6 个真实 MySQL 集成测试（双门店隔离、筛选、去重、批次口径、日期边界、分页、执行计划）
  与 13 个 MockMvc 契约测试。
- 建立初始库存导入纵向切片：`domain.importbatch` 端口与值对象、`PostInitialInventoryImport`
  应用用例（门店校验 → SHA-256 查重 → 有效批次检查 → Excel 解析 → 行级校验 → 全有或全无过账）、
  EasyExcel 解析器按表头名定位「条码」「库存数量」并把整行 12 列写入审计 JSON、MyBatis Adapter
  单事务写批次/原始行/库存 upsert/INITIAL_BALANCE 流水（先 SELECT 现有余额再计算，
  满足流水平衡 CHECK）；
- 新增 `POST /api/stores/{storeId}/inventory/import` REST 接口：multipart 上传 .xls/.xlsx
  （上限 5MB），可选 `warehouseId`（不传则仓库待分配），200 返回批次终态
  （POSTED/FAILED + 行级错误明细）；
- 新增 12 个用例单测、7 个解析器单测、9 个 MockMvc 契约测试与 4 个真实 MySQL 全链路集成测试
  （真实解析器 + 真实用例 + 真实 Adapter，验证批次/原始行/库存累加/流水与失败路径）。

### 调整

- 将 Controller 内嵌 Response 拆分为独立顶层类型，按经营数据功能包组织；
- 将 `DailySalesResponse` 明确命名为 `StoreDailySalesResponse`，为商品日销售扩展预留语义；
- Controller 仅负责 HTTP 适配，业务校验集中在应用层；
- 使用专用查询参数异常，避免把未知编程错误错误映射为 HTTP 400；
- 查询参数在访问 Repository 前完成校验；
- `StoreRepository` 兼容性新增 `findActiveById` 默认实现，MyBatis Adapter 覆盖为按主键精确查询；
- `ApiExceptionHandler` 仅追加新异常与 Spring 绑定异常映射，既有处理器保持不变；
- 追加初始库存导入的 400（文件级错误/跨门店仓库/缺上传文件/超大小）与 409（重复文件/有效批次）
  异常映射；
- `application.yml` 增加 multipart 上传大小上限 5MB；
- 导入批次数据访问集中在 `ImportBatchMapper.xml`；库存 upsert 使用 MySQL 8.0.19+ 行别名语法，
  冲突行只累加数量与版本号、不覆盖仓库分配。

### 安全

- 数据库密码和 DashScope API Key 改为环境变量读取，不进入源码或 Git 历史；
- 文件查重基于内容 SHA-256（同店同类型），与数据库唯一键双重防护。

### 验证

- `mvn test` 通过：应用层 3 个测试、Controller 7 个测试，共 10 个测试；
- 保持三个既有接口的 URL 和成功响应 JSON 字段不变；
- 多门店商品查询全量回归：36/36 测试通过（应用层 10、真实 MySQL 集成 6、启动模块 20），
  测试数据残留 0；
- 三个既有接口与 `GET /api/stores` 复用未受影响；
- 初始库存导入全量回归：`mvn -pl haowugou-bootstrap -am test`（注入 HAOWUGOU_DB_PASSWORD）
  68/68 测试通过（应用层 22、基础设施 13、启动模块 33），0 失败；
- 真实 POS 文件冒烟测试：上传桌面《商品资料1.xls》→ 批次 POSTED，原始行 12 列 JSON 审计、
  库存 20.000（仓库待分配）、流水 0.000→20.000 全部落库；同内容重复上传返回 409；
  清理后测试数据残留 0。
