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
- 建立每日销售导入与库存扣减纵向切片：`domain.salesimport` 独立端口与值对象、
  `PostDailySalesImport` 应用用例（门店与业务日期校验 → SHA-256 查重 → 当日有效批次检查 → 解析
  → 行级校验 → 未知条码建 PENDING 商品 → 按 `(商品, 供应商)` 归并销售事实、按商品汇总净销量
  → 全有或全无过账）、EasyExcel 解析器按表头名定位并丢弃合计行、MyBatis Adapter 单事务写
  批次/原始行/待完善商品/`daily_product_sales`/库存 upsert/`SALE_OUT`·`SALE_RETURN` 流水；
- 新增 `POST /api/stores/{storeId}/sales/import` REST 接口：multipart 上传 .xls/.xlsx，必填
  `businessDate`（不晚于今天），200 返回批次终态与 `salesRows`/`pendingProductsCreated`/
  `deductedProducts` 摘要；
- 新增 19 个用例单测、9 个解析器单测、13 个 MockMvc 契约测试、8 个真实 MySQL 全链路集成测试
  与 1 个真实 POS 文件端到端测试（文件路径取自环境变量，结束回滚不留数据）。
- 建立导入批次查询与撤销纵向切片（跨导入类型共用，端口复用 `domain.importbatch`）：
  `ImportBatchQueryRepository`/`ImportBatchReversalRepository` 端口与值对象、
  `ImportBatchQuery` 与 `ReverseImportBatch` 应用用例（门店校验、分页与日期区间校验、
  可撤销性判定）、`ImportBatchAdminMapper` 单事务撤销实现（先翻状态兼作乐观锁 → 读原流水
  → 读当前余额 → `CASE product_id` 批量回滚库存 → 与原流水 1:1 写 `REVERSAL` 反向流水，
  `balance_before` 取库内当前值以满足流水平衡 CHECK，`business_date` 沿用原流水）；
- 新增 `GET /api/stores/{storeId}/import-batches`（按导入类型、批次状态、数据日期区间筛选，
  按上传时间倒序分页）、`GET /api/stores/{storeId}/import-batches/{batchId}`（批次详情 +
  问题行独立分页，只返回非 VALID 行）、
  `POST /api/stores/{storeId}/import-batches/{batchId}/reverse`（撤销，必填操作人与原因）
  三个 REST 接口；批次不属于该门店按 404 处理，不泄露其他门店批次的存在。
- 新增 13 个用例单测、12 个 MockMvc 契约测试与 7 个真实 MySQL 全链路集成测试
  （覆盖销售批次撤销后库存与余额链复原、撤销后同文件同日期重传、重复撤销拒绝、
  撤销初始库存导致负库存、跨门店隔离、失败批次问题行与不可撤销、问题行独立分页）。

### 变更

- 文件指纹查重从 `uk_import_batch_file_hash (store_id, import_type, file_hash)` 改为建在新增
  生成列 `active_file_hash` 上的 `uk_import_batch_active_file_hash`，与
  `active_sales_date`/`active_initial_inventory` 统一口径：只有 POSTED 批次占用坑位。
  撤销与失败批次释放指纹，同一份文件可以重传（业务日期填错时文件本身没问题，
  「改内容让哈希变化」对该场景不成立）。两条导入链路的 `countBatchByFileHash` 同步改查生成列。
  迁移脚本：`database/migration/2026-08-30-import-batch-active-file-hash.sql`（不幂等）。

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
  冲突行只累加数量与版本号、不覆盖仓库分配；
- 销售导入不复用初始库存导入的端口与 Mapper：行模型与写入表都不同，且给 `ImportFileParser`
  加第二个实现会让既有配置类的按类型注入变成歧义注入；
- 追加每日销售导入的 400（文件级错误/业务日期缺失、格式错误或晚于今天）与 409（重复文件/
  当日已有有效销售批次）异常映射；既有处理器保持不变。

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
  清理后测试数据残留 0；
- 每日销售导入全量回归：`mvn -pl haowugou-bootstrap -am test`（注入 HAOWUGOU_DB_PASSWORD）
  119/119 测试通过（应用层 41、基础设施 22、启动模块 56），0 失败 0 跳过；
- 真实 POS 销售文件端到端：导入桌面《商品销售汇总.xls》（899 数据行）→ 批次 POSTED、
  899 原始行全 VALID、485 条销售事实、485 个待完善商品、485 条扣减流水（含 1 行退货）；
  销售数量合计 988.000、销售收入合计 7342.00，与文件自带合计行逐项一致；
  库存余额合计等于流水合计；测试结束回滚，数据残留 0。
