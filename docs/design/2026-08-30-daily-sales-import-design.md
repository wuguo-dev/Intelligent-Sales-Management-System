# 每日销售导入与库存扣减设计（POS 商品销售汇总 .xls → MySQL）

- 状态：已实现并用真实 POS 文件验证落库（2026-08-30）
- 功能切片：`codex/daily-sales-import`
- 对应架构规范：好物购项目整体架构规范 v1.0（§5 纵向切片、§9 多门店隔离、§12 事务、§13 导入模块、§17 测试）
- 前序切片：`docs/superpowers/specs/2026-08-27-initial-inventory-import-design.md`

## 1. 业务目标

开发顺序基线第 3 项「按门店导入每日销售和库存扣减」。核心验证目标：**真实 POS 导出的《商品销售汇总》
（.xls）能否成功入账为销售事实并正确扣减库存**。

## 2. 真实文件实测结果

实测桌面 `商品销售汇总.xls`（235KB，单 sheet「商品销售汇总」，901 行 × 15 列，全部单元格文本）：

| 列 | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 表头 | 条码 | 商品名称 | 本期\|销售数量 | 选中机构库存数量 | 当前机构最后进价 | 当前机构售价 | 销售毛利率 | 本期\|销售收入 | 销售占比 | 日均销售 | 同期\|销售收入 | 同期\|销售毛利率 | 同期\|销售数量 | 品类名称 | 供应商名称 |

实测统计（901 行 = 表头 1 + 数据 899 + 合计 1，下表口径为 899 个业务数据行）：

| 事实 | 数值 | 对设计的影响 |
|---|---|---|
| 末行为合计行（条码与商品名称皆空，数量 988.00、收入 7342.00） | 1 行 | 必须识别并丢弃，否则触发「条码为空」整批失败 |
| 不同条码 | 892（899 行） | 同条码多供应商，7 行重复 |
| `(条码, 供应商名称)` 重复 | 0 | 与 `uk_daily_sales_batch_product_supplier` 同口径 |
| 条码格式非法 | 0 | 沿用 `^[0-9A-Za-z-]+$` |
| 数量/收入无法解析 | 0 | 仍需防御性校验 |
| 数量与收入同时为 0 | 413 行（余 486 行有销售） | POS 把当期未销售商品一并导出 |
| 数量为负 | 1 行（第 773 行 -2，收入 -4） | 退货，`SALE_RETURN` |
| 数量为 0 但收入非 0 / 反之 | 0 / 0 | 跳过条件取「两者同时为 0」才安全 |
| 最大小数位：数量 0、收入 1、毛利率 2 | — | 均在 DECIMAL(18,3)/(18,2)/(9,4) 内 |
| `收入-数量×进价` 与 `收入×毛利率` 差 > 0.05 | 45 行 | 第 4 列是「**当前**最后进价」，不能用来算毛利额 |

## 3. 已确认决策

| 决策 | 理由 |
|---|---|
| 沿用方案 A：上传即同步校验过账，全有或全无 | 与初始库存导入一致，批次直接落终态 |
| `businessDate` 为**必填**请求参数 | 文件无日期列（本期/同期由 POS 导出时选区间），`import_batch.data_date` 与 `uk_import_batch_active_sales_date` 必须有值，无法从文件推导 |
| 毛利额 = `销售收入 × 销售毛利率 ÷ 100`（HALF_UP 2 位） | 第 4 列是当前最后进价，实测 45 行与 POS 毛利率矛盾；POS 毛利率是其按销售当时成本算出的口径 |
| `reported_gross_profit_rate` 原样存 POS 毛利率 | 库表注释「POS 原始毛利率百分数，仅供核对」 |
| 数量与收入同时为 0 → 跳过，不写销售事实、不产生流水、不创建待完善商品 | 413/899 行无分析价值；`chk_inventory_movement_nonzero` 也禁止 0 流水；原始行仍留审计 |
| 条码与商品名称皆空的行 → 解析器丢弃（合计行/空行） | 属文件结构而非数据行，与上一片丢弃全空行同类 |
| 未知条码 → 自动创建 `data_status='PENDING'` 待完善商品并入账 | 架构规范 §17.2「未知条码可以入账并形成待完善商品」；上一片设计文档已明确该规则预留给每日销售导入（销售事实必须入账） |
| 品类/供应商按名称查现有主数据，查不到记 NULL，不自动创建 | 避免销售文件污染主数据；商品本就是 PENDING 待完善 |
| 不写 `product_supplier` 关联 | 供应商关联属商品主数据维护范围，留给商品编辑/资料导入 |
| 销售事实按 `(商品, 供应商)` 落库；库存流水按**商品**汇总 | 前者匹配 `uk_daily_sales_batch_product_supplier`，后者保证每商品每批次一条流水且余额可链式追溯 |
| 多个未识别供应商归并为一条「未知供应商」记录 | 库表注释：`supplier_key` 取 0「确保同一批次、商品只有一条未知供应商记录」 |
| 归并行的 `reported_gross_profit_rate` 记 NULL | 该列语义是 POS 原始值供核对，归并后无法归属单一原始值；毛利额仍为各行求和 |
| 商品净销量为 0（正负抵消）→ 不产生流水 | `chk_inventory_movement_nonzero` |
| 新建独立端口 `DailySalesFileParser` / `DailySalesImportRepository` | 行模型与写入表都不同；且若给 `ImportFileParser` 加第二个实现，会让既有 `InitialInventoryImportConfiguration` 按类型注入变成歧义注入 |
| 不改动初始库存导入切片任何文件 | 最小改动；两片 SQL 各自独立（§7.3 不引入无替换需求的抽象） |

## 4. 接口契约

`POST /api/stores/{storeId}/sales/import`，multipart/form-data 字段 `file`（.xls / .xlsx），必填 query
`businessDate`（`yyyy-MM-dd`）。

| 情形 | 响应 |
|---|---|
| 校验通过并过账 | 200 + `status:"POSTED"` + 行数摘要（含 `salesRows`、`pendingProductsCreated`、`deductedProducts`） |
| 行级内容错误 | 批次记 FAILED 留审计，200 + `status:"FAILED"` + `errors[]`（行号+条码+原因，最多 50 条），无任何销售或库存变化 |
| 文件级错误（扩展名/空文件/解析失败/表头缺列/无数据行） | 400 Problem Detail，不落批次 |
| `businessDate` 缺失或格式错误 | 400（Spring 绑定异常已映射） |
| `businessDate` 晚于今天 | 400「销售业务日期不能晚于今天」 |
| 门店不存在或未启用 | 404（复用 `StoreNotFoundException`） |
| 同店同类型同 SHA-256 已导入 | 409「该文件已导入过」（`uk_import_batch_file_hash`） |
| 该店该业务日期已有 POSTED 销售批次 | 409「该业务日期已有有效销售批次」（`uk_import_batch_active_sales_date`；撤销属基线第 4 项） |

行级错误口径：条码为空、条码格式非法、条码超 64 字符、数量/收入无法解析、数量小数位 > 3、
收入小数位 > 2、毛利率小数位 > 4、`(条码, 供应商)` 在文件内重复、未知条码缺商品名称（无法创建待完善商品）。

## 5. 用例流程（PostDailySalesImport）

```
门店ID/业务日期校验 → 文件级校验（扩展名/空文件）→ 门店有效性(404) → SHA-256 → 文件查重(409)
→ 该日 POSTED 批次检查(409) → 解析（表头/合计行/空行，文件级错误→400）
→ 行级校验（条码、数量、收入、毛利率、文件内重复）
→ 有行错误：批次 FAILED + 原始行落库，返回 FAILED 摘要（无销售、无库存变化）
→ 无行错误：条码批量查商品 → 未知条码按名称/品类创建 PENDING 商品
→ 供应商按名称批量解析（未识别记 NULL）
→ 按 (商品, supplier_key) 归并销售事实；按商品汇总净销量得扣减量
→ 单事务过账（批次 POSTED + 原始行 VALID + daily_product_sales + 库存 upsert + SALE_OUT/SALE_RETURN 流水）
```

库存扣减：净销量 > 0 → `SALE_OUT`，`quantity_change = -净销量`；净销量 < 0 → `SALE_RETURN`，
`quantity_change = -净销量`（为正）；净销量 = 0 → 无流水。库存行不存在时插入（数量为负、仓库待分配），
允许形成负库存（架构规范 §17.2）。余额按「先 SELECT 现有库存 → Java 计算」得到，满足
`chk_inventory_movement_balance`。

## 6. 非目标

- 批次查询、撤销与同日重传（基线第 4 项，届时补 REVERSED/REVERSAL 链路）
- 经营分析区间聚合接口（基线第 5 项）
- 商品资料导入与商品编辑写接口、仓库批量分配
- 自动创建品类与供应商主数据、`product_supplier` 关联维护

## 7. 风险与说明

- POS 导出区间由操作员在 POS 侧选择；本接口按传入 `businessDate` 归属整份文件。若操作员导出的是
  多天区间，数据会被记到单一业务日期。该约束由操作流程保证，接口只能校验「不晚于今天」。
- 失败批次的 `file_hash` 同样占用唯一键，修正文件内容后哈希改变即可重导（与上一片一致）。
- 本次无 DDL；误导入数据的清理依赖基线第 4 项的批次撤销，当前开发期可手工 DELETE。

## 8. 实现与验证结果（2026-08-30）

各层落地文件：

| 层 | 文件 |
|---|---|
| domain | `domain/salesimport/`：`DailySalesFileParser`、`DailySalesImportRepository`、`ParsedSalesFile/Row`、`DailySalesPosting`、`DailySalesFactRow`、`SalesMovement`、`PendingProductDraft`、`DailySalesImportResult` |
| application | `application/salesimport/PostDailySalesImport` + 4 个异常 |
| infrastructure | `fileimport/PosDailySalesExcelFileParser`、`persistence/salesimport/`（Mapper + Adapter + 数据对象）、`resources/mapper/DailySalesImportMapper.xml` |
| bootstrap | `controller/salesimport/`（Controller + 响应模型）、`config/DailySalesImportConfiguration`、`ApiExceptionHandler` 追加 3 个映射 |

测试：应用层 19、解析器 9、MockMvc 契约 13（STRICT JSON）、真实 MySQL 全链路集成 8、
真实 POS 文件端到端 1，全部通过；全量回归 119/119。

真实文件端到端结果（`RealPosDailySalesFileImportTest`，读环境变量 `HAOWUGOU_POS_SALES_FILE`，
结束回滚不留数据）：

| 指标 | 结果 |
|---|---|
| 批次 | POSTED，899 原始行全 VALID，0 行级错误 |
| 销售事实 | 485 条 |
| 待完善商品 | 485 个（该店此前无这些条码） |
| 库存流水 | 485 条 SALE_OUT/SALE_RETURN，含第 773 行退货 |
| 销售数量合计 | 988.000 —— **与文件合计行 988.00 一致** |
| 销售收入合计 | 7342.00 —— **与文件合计行 7342.00 一致** |
| 库存余额合计 | 等于流水合计，即净销量的相反数 |

其中 486 个有销售的行落 485 条事实：有两行共用同一条码、供应商名称不同且两者都不在
`supplier` 主数据中，都解析为 `supplier_id = NULL`，按 `supplier_key = IFNULL(supplier_id, 0)`
只能合成一条事实——正是第 3 节「多个未识别供应商归并为一条未知供应商记录」的既定行为，
合并后数量与收入求和，故与文件合计行仍然逐项一致。