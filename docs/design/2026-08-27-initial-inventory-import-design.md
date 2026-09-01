# 初始库存导入设计（POS 商品资料 .xls → MySQL）

- 状态：已批准（2026-08-27 计划批准）
- 功能切片：`codex/initial-inventory-import`
- 对应架构规范：好物购项目整体架构规范 v1.0（§5 纵向切片、§12 事务、§13 导入模块、§17 测试）

## 1. 业务目标

路线图第 1 项「按门店导入初始库存」。核心验证目标：**真实 POS 导出的 Excel（.xls）数据能否成功导入数据库**。

实测真实文件 `商品资料1.xls`（POS 商品资料导出）：

| 表头 | 商品名称 | **条码** | 单位 | 供应商名称 | 含税成本价 | 售价 | 毛利率 | 品类编码 | 品类名称 | **库存数量** | 商品备注 | 提成率/固定值 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 数据 | 130g花王香皂 | 9556155017024 | 块 | 天和日化 | 4 | 5 | 20 | 010204 | 香皂 | 20 | (空) | 0% |

解析细节：全部单元格为文本类型（数字也是 STRING）、表头在第 1 行、末尾存在空行、无仓库列。

用户工作流：**导入时不带仓库 → 后续在商品编辑页面分配仓库**。数据库已支持：`store_product_inventory.warehouse_id` 允许 NULL（注释「待分配时可为空」），视图 `v_product_inventory_query` LEFT JOIN warehouse。**本次无数据库变更**。

## 2. 已确认决策

- 方案 A：上传即同步校验过账，全有或全无（严格报错整批拒：任何行级错误 → 整批 FAILED，不产生部分库存变化）
- 只做导入接口（批次查询/撤销属后续路线图项）
- 导入文件 = POS 12 列商品资料格式，按表头名称定位「条码」「库存数量」两列，其余列忽略
- 导入时不强制仓库；接口提供**可选** `warehouseId` 参数，不传则 `warehouse_id=NULL`（待分配）
- 与架构规范 §17.2「未知条码可以入账并形成待完善商品」的差异：该规则预留给**每日销售导入**（销售事实必须入账）；初始库存导入按本次交互确认执行严格拒。开发任务文档后续应同步此口径。

## 3. 接口契约

`POST /api/stores/{storeId}/inventory/import`，multipart/form-data，字段 `file`（.xls / .xlsx），可选 query `warehouseId`。

| 情形 | 响应 |
|---|---|
| 校验通过并过账 | 200 + `{batchId, status:"POSTED", totalRows, successRows, errorRows, errors:[]}` |
| 行级内容错误（未知条码/负数量/重复条码/数量无法解析/条码为空） | 批次记 FAILED 留审计，200 + `status:"FAILED"` + errors[]（行号+条码+原因，最多 50 条） |
| 文件级错误（扩展名不支持/空文件/解析失败/表头缺「条码」或「库存数量」/无数据行） | 400 Problem Detail，不落批次 |
| 门店不存在或未启用 | 404（复用 `StoreNotFoundException`） |
| 同店同类型同 SHA-256 已导入 | 409「该文件已导入过」（对应 `uk_import_batch_file_hash`） |
| 该店已有有效初始库存批次 | 409「已有有效初始库存批次」（对应 `uk_import_batch_active_initial`；撤销属路线图第 4 项） |
| warehouseId 不合法（≤0 或不属于该门店） | 400「跨门店仓库」 |

## 4. Excel 解析规则

- 按表头名称定位「条码」「库存数量」两列（不按固定列序），其余 10 列忽略；表头行 = 第 1 行
- 单元格按文本读取；条码校验正则 `^[0-9A-Za-z\-]+$`（防科学计数法形态的条码入账）
- 完全空行跳过；数量 0 → 跳过（POS 会导出零库存商品，不算错误，不产生库存/流水）；数量 < 0 → 行错误；数量小数位 > 3 → 行错误（库表 DECIMAL(18,3)）；同文件条码重复 → 行错误
- `import_raw_row`：`row_number` 存 Excel 实际行号（数据从第 2 行起），`raw_data` 存整行 12 列文本 JSON，`parse_status` 为 VALID/INVALID

## 5. 用例流程（PostInitialInventoryImport）

```
门店校验 → 文件级校验（扩展名/空文件）→ SHA-256 → 文件查重(409) → 有效批次检查(409)
→ 可选 warehouseId 校验 → 解析（表头/行，文件级错误→400）
→ 行校验（条码格式/空/重复、数量解析/符号/小数位）→ 条码批量查商品（未知→行错误）
→ 有行错误：批次 FAILED + 原始行（VALID/INVALID）落库，返回 FAILED 摘要
→ 无行错误：单事务过账（批次 POSTED + 原始行 VALID + 库存累加 + INITIAL_BALANCE 流水），返回 POSTED 摘要
```

`data_date` / `business_date` = 导入当天（注入 `Supplier<LocalDate>`，默认为 `LocalDate::now`，测试注入固定日期）。

## 6. 入库语义（单事务，@Transactional 于 Adapter 写方法）

- `import_batch`：`import_type=INITIAL_INVENTORY`、终态直接落库。**状态机说明**：同步过账下 `VALIDATING/POSTING` 为事务内瞬时状态，仅终态（POSTED/FAILED）落库；数据库 CHECK 与唯一约束不变，任何入口不得绕过用例直接改批次状态（§13）
- 库存 upsert：`current_quantity = current_quantity + 行数量`、`version + 1`；冲突时**不动**已有 `warehouse_id`（不静默移动货品），新插入时按参数或 NULL
- `inventory_movement`：INITIAL_BALANCE，`quantity_change=+数量`，`balance_before/after` 由 Adapter 先 SELECT 受影响行现有余额再在 Java 计算（满足 `chk_inventory_movement_balance`）；0 数量行不产生流水
- 并发安全：同一门店的并发初始导入被 `uk_import_batch_active_initial`（生成列）在库层阻止；库存更新为单行原子 upsert + 版本号。未来每日销售导入需在此基础上加行锁（记入 findings）
- 幂等：`uk_import_batch_file_hash(store_id, import_type, file_hash)` + 应用层查重双重保证

## 7. 分层设计（包名对齐架构规范 §5）

- **domain** `com.haowugou.domain.importbatch`：端口 `ImportBatchRepository`（existsFileHash / existsActiveInitialBatch / findProductIdsByBarcodes / postBatch / saveFailedBatch）、端口 `ImportFileParser`、`ImportFileFormatException`、值对象 `ParsedImportRow` / `ParsedImportFile` / `ImportPosting` / `ImportPostRow` / `ImportFailure` / `ImportFailureRow` / `ImportRowError` / `ImportBatchResult`
- **application** `com.haowugou.application.inventoryimport`：用例 `PostInitialInventoryImport`（返回 domain `ImportBatchResult`）、异常 `InvalidImportFileException`(400) / `ImportWarehouseException`(400) / `DuplicateImportFileException`(409) / `ActiveInitialBatchExistsException`(409)，复用 `StoreNotFoundException`
- **infrastructure**：
  - `fileimport.PosProductExcelFileParser`（EasyExcel 4.0.3 已在 infrastructure 依赖；`Map<Integer,String>` 行读取，Jackson 生成 raw_data JSON，为此新增 `jackson-databind` 依赖）
  - `persistence.importbatch.ImportBatchMapper` + `resources/mapper/ImportBatchMapper.xml`（批次插入 useGeneratedKeys、条码批量查询、原始行批插、库存 upsert、流水批插、两个存在性查询）+ `MybatisImportBatchRepository`（@Repository + @Transactional，日志记录批次/门店/日期/结果，不记录文件内容）
- **bootstrap**：`controller.importbatch.InitialInventoryImportController` + `InitialInventoryImportResponse`、`config.InitialInventoryImportConfiguration`、`ApiExceptionHandler` 追加 4 个异常映射 + 缺文件/超大小映射、`application.yml` 加 multipart 上限（5MB）

复用：`StoreRepository.findActiveById`、`WarehouseRepository.existsByStoreIdAndId`（跨门店仓库校验）、`StoreNotFoundException`、既有组装/契约测试模式。

## 8. 测试范围

1. 应用层单测（内存替身）：成功过账（含 SHA-256 正确性、warehouseId 传递）、未知条码 FAILED、负数量 FAILED、重复条码 FAILED、0 数量跳过、空文件/扩展名/无数据行/表头缺失 400、门店不存在 404、同文件 409、已有有效批次 409、跨门店仓库 400
2. 解析器单测（POI 生成 .xls/.xlsx 夹具）：12 列表头按名定位、全文本单元格、前导零条码、小数数量、空行/0 行、缺表头报错、损坏文件报错
3. MockMvc 契约测试（standalone + 真实用例 + 内存替身，JsonCompareMode.STRICT）：POSTED 契约、FAILED 契约、400/404/409
4. MySQL 集成测试（真实库，高位 ID ≥8e18 + `IT-` 前缀 + JDBC Connection 回滚）：**全链路**（真实解析器 + 真实用例 + 真实 Adapter，店内/仓库门面用 JDBC 小替身）：批次/原始行/库存累加/流水 before-after、0 行跳过、同文件 409、二次导入 409、失败批次不产生库存变化、残留 0
5. 冒烟测试：启动应用 → SQL 预置门店与商品（条码 9556155017024）→ curl 上传桌面真实 `商品资料1.xls` → 查库验证 batch + inventory + movement → 清理

## 9. 不在本次实现的内容

- 批次查询、撤销与同日重传（路线图第 4 项，届时补 REVERSED/REVERSAL 链路）
- 批量分配仓库接口与商品编辑页仓库字段（路线图第 7 项前段）
- 商品资料导入（同一 POS 文件 12 列 → product 表；本次冒烟测试用 SQL 预置商品）
- 每日销售导入（届时按 §17.2 落实「未知条码入账形成待完善商品」）
- ArchUnit 架构测试（规范 §17.2 建议项，另立切片）

## 10. 回滚方式

- 代码：回滚 `codex/initial-inventory-import` 分支合并（或 revert 对应提交）
- 数据：本次无 DDL；误导入的数据按路线图第 4 项的批次撤销功能清理，当前开发期可手动 DELETE（高位测试 ID 与 `IT-` 前缀数据仅存在于测试）
