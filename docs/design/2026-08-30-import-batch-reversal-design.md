# 导入批次查询、撤销与同日重传设计

- 状态：已实现（迁移待应用）—— 四层代码与 151 个测试就位；`active_file_hash` 迁移未在本地库执行，
  7 个真实 MySQL 全链路集成测试因此尚未实跑
- 功能切片：`codex/initial-inventory-import`（沿用当前分支）
- 对应架构规范：好物购项目整体架构规范 v1.0（§5 纵向切片、§9 多门店隔离、§12 事务与撤销、
  §13 导入状态机、§17.2 强制场景、§18 审计）
- 前序切片：`docs/superpowers/specs/2026-08-27-initial-inventory-import-design.md`、
  `docs/superpowers/specs/2026-08-30-daily-sales-import-design.md`

## 1. 业务目标

开发顺序基线第 4 项「导入批次查询、撤销和同日重传」。补上前两个导入切片留下的缺口：
`ActiveInitialBatchExistsException` 与 `PostedSalesBatchExistsException` 的注释都写着
「需先撤销后才能重新导入」，但撤销链路不存在——批次导错后无法从 API 层回退，只能改库。

三件事：

1. **查询**：按门店列出导入批次（分页 + 类型/日期/状态筛选），查单个批次详情含行级错误明细；
2. **撤销**：`POSTED → REVERSED`，通过反向流水回滚库存，不删原销售与原流水；
3. **同日重传**：撤销后同一门店同业务日期可以重新导入。

## 2. 数据库现状核查

撤销所需的表结构已在建表脚本中就位，**除文件指纹唯一键外不需要新增字段**。

| 能力 | 支撑物 | 结论 |
|------|--------|------|
| 批次终态 | `import_batch.status` 枚举含 `REVERSED` | 可用 |
| 撤销审计 | `reversed_at` / `reversed_by` / `reversed_reason` | 可用 |
| 反向流水 | `inventory_movement.movement_type` 含 `REVERSAL`；`reversal_of_id` 外键自引用 | 可用 |
| 一条原流水只能冲一次 | `uk_inventory_movement_reversal (reversal_of_id)` | 可用 |
| REVERSAL 必须有来源 | `chk_inventory_movement_reversal_ref` | 可用 |
| 撤销后释放业务唯一键 | 生成列 `active_sales_date` / `active_initial_inventory` 仅在 `status='POSTED'` 时非空 | 可用 |
| 撤销后销售指标自动剔除 | 视图 `v_posted_daily_product_sales` 内联 `b.status = 'POSTED'` | 可用 |

规范 §17.2 的两条强制场景「已撤销批次不进入有效销售分析」「撤销批次恢复库存且保留审计历史」
正是本切片的验收口径，§18「业务撤销必须记录操作人、时间和原因」对应 `reversed_*` 三个字段。

### 2.1 关键发现：销售事实表一行都不用动

`v_posted_daily_product_sales` 在视图层就 `INNER JOIN import_batch` 并要求 `status = 'POSTED'`。
批次状态翻成 `REVERSED` 后，该批次的销售事实自动从所有分析查询中消失，`daily_product_sales`
无需删除或标记。这正好落在规范 §12「撤销通过反向流水完成，不删除原销售和原流水」上。

因此撤销动作与批次类型**无关**：无论原流水是 `INITIAL_BALANCE` 还是 `SALE_OUT`/`SALE_RETURN`，
都是「读原流水 → 写符号相反的 REVERSAL → 库存回加」。一个端口、一个用例覆盖两种批次类型。

### 2.2 需要迁移：文件指纹唯一键不含状态

`uk_import_batch_file_hash (store_id, import_type, file_hash)` 没有状态维度，撤销后重传
**同一个文件**会被 409 挡住。触发场景真实存在：`businessDate` 填错（8-29 的文件填成 8-28），
文件本身没问题，撤销后要拿原文件改日期重传——哈希键不含 `data_date`，会挡。
「改内容让哈希变化即可重导」对这个场景不成立。

采用与现有两个业务不变量同一套手法，新增生成列并换键。脚本单独放
`database/migration/2026-08-30-import-batch-active-file-hash.sql`，不改建表脚本本体——
规范 §11 说开发期手动执行 SQL、§22 要求「数据库变更有独立 SQL 或版本化迁移」，
独立文件两边都满足（建表脚本同步更新列定义，供新库一次建成）：

```sql
ALTER TABLE `import_batch`
    ADD COLUMN `active_file_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (
            CASE WHEN `status` = 'POSTED' THEN `file_hash` ELSE NULL END
        ) STORED COMMENT '有效批次的文件指纹；非 POSTED 释放坑位',
    DROP INDEX `uk_import_batch_file_hash`,
    ADD UNIQUE KEY `uk_import_batch_active_file_hash` (`store_id`, `import_type`, `active_file_hash`);
```

谓词取 `status = 'POSTED'` 而非「非终态也算占用」，与 `active_sales_date`、
`active_initial_inventory` 完全一致，三列同一口径。已核实当前实现只会写入 `POSTED` 与
`FAILED` 两种状态（`ImportBatchResult` 只定义了这两个常量，方案 A 同步过账不落
`VALIDATING`/`POSTING` 中间态），所以不存在「中间态批次占键」的现实场景。

由此 `FAILED` 也一并释放坑位——失败批次没有产生任何业务数据，同一个文件修好外部原因
（例如先补齐商品资料）后重传属于正常操作，现状要求改内容才能重传是无谓限制。

如果以后改成异步导入并持久化 `VALIDATING`/`POSTING`，需要重新评估这三列的谓词，
届时并发上传同一文件的两个请求都能通过查重。现有两列本来就有同样的性质，不是本次引入的。

`ALTER TABLE` 加 STORED 生成列会重建表，当前数据量下无影响；三个子句写在一条语句里，
借 MySQL 8.0 的原子 DDL 避免出现「旧键已删、新键未建」的中间态。

副作用：查重语义从「历史上导过」变成「当前有效批次里导过」。两条链路各自的查重 SQL
都要改——`ImportBatchMapper.xml` 与 `DailySalesImportMapper.xml` 里同名的
`countBatchByFileHash` 现在都是裸 `file_hash = #{fileHash}`，需改为查 `active_file_hash`；
`ImportBatchRepository.existsFileHash` 与 `DailySalesImportRepository` 对应方法的 Javadoc
以及重复文件的提示语也要同步改口径。

## 3. 端口边界

**复用 `domain.importbatch` 包**（已确认）。但不扩展 `ImportBatchRepository`——它的 Javadoc
明确是「初始库存导入的持久化边界」，`existsActiveInitialBatch` / `postBatch` 都是库存导入语义，
塞进查询与撤销会让单个接口承担三种职责。同包新增两个端口：

| 端口 | 职责 |
|------|------|
| `ImportBatchQueryRepository` | 批次分页列表、批次详情、行级错误明细（只读） |
| `ImportBatchReversalRepository` | 单事务撤销：翻状态 + 写 REVERSAL 流水 + 库存回加 |

拆两个而非一个，因为读写事务边界不同，且查询会被未来的批次列表页面独立使用。
`DailySalesImportRepository` 已有跨包引用 `domain.importbatch.ImportFailure` 的先例，
共享值对象（如 `ImportBatchStatus`）放 `domain.importbatch` 供两条导入链路使用。

### 3.1 PageResult 需要挪位置

`PageResult<T>` 现在在 `domain.product` 包里。批次分页复用它会让 `domain.importbatch`
依赖 `domain.product`，而分页是与业务域无关的通用结果类型。挪到 `domain.pagination`
（9 个引用文件改 import，纯搬迁），与上一次异常收拢同性质。

## 4. 撤销语义

### 4.1 流程（单事务）

```text
[application] 校验门店存在
  → 按 (batchId, storeId) 读批次摘要，不存在或跨门店 → 404
  → 状态非 POSTED → 409（FAILED/REVERSED/VALIDATING/POSTING 都不可撤销）
  → 校验 reversedBy / reversedReason 非空且不超长 → 400
[adapter，单事务]
  → 读该批次全部 inventory_movement（按 store_id 限定）
  → SELECT 各商品当前库存余额
  → 逐条算 balance_before / balance_after，串成余额链
  → 每条原流水写一条 REVERSAL（quantity_change 取反，reversal_of_id 指向该原流水）
  → 库存按每个商品的反向净量 upsert
  → 批次 status = REVERSED，写 reversed_at / reversed_by / reversed_reason
```

**反向量与余额链都在 adapter 里算，不在 application。** 两条现有链路的分工是
「application 决定变多少（`buildMovements` 算净销量），adapter 算 `balance_before`/
`balance_after` 并原子落库（`balancesBefore` + `insertMovements`）」。撤销的「变多少」只能
从数据库里的原流水推出来，application 拿不到；若为此在端口上开一个「读原流水」的方法再回头
调写入方法，一次撤销就被拆成两次端口调用，读与写之间插入的并发导入会把余额链算错。
所以端口只暴露一个 `reverse(命令)`，事务边界完整——与 `postBatch(posting)` 同形。
代价是反向算术由 MySQL 集成测试覆盖而非应用层单测，这与现有链路的 `balancesBefore`
一致（它今天也只有集成测试覆盖）。

**REVERSAL 与原流水必须 1:1**，不能按商品汇总成一条：`uk_inventory_movement_reversal`
是 `reversal_of_id` 上的唯一键，而 `chk_inventory_movement_reversal_ref` 要求每条 REVERSAL
都带来源。现有两条链路每批次每商品最多写一条流水（销售侧在 `PostDailySalesImport` 就按
净量归并了），所以当下「逐条」与「按商品」结果相同；按逐条实现是为了让约束天然成立，
且未来出现一批多流水时不用改。同一商品有多条原流水时，余额链要按写入顺序递推，
不能各自用同一个 `balance_before`——`chk_inventory_movement_balance` 会拦。

`quantity_change` 取反不会触碰 `chk_inventory_movement_nonzero`：原流水本就非 0。

写入 SQL 要新增语句，两条现有的都不能用：`ImportBatchMapper.xml` 的 `insertMovements`
把 `'INITIAL_BALANCE'` 硬编码在 SQL 里，`DailySalesImportMapper.xml` 的同名语句虽然
参数化了 `movement_type`，但两者都不写 `reversal_of_id`。

状态检查必须在事务内配合行锁或乐观检查（`UPDATE ... WHERE status = 'POSTED'` 判受影响行数），
防止并发双重撤销把库存冲两次。`uk_inventory_movement_reversal` 是最后一道防线：
第二次撤销写同一个 `reversal_of_id` 会撞唯一键。

### 4.2 按批次类型的表现差异

| 原批次类型 | 原流水 | 撤销后 | 附带效果 |
|-----------|--------|--------|---------|
| `INITIAL_INVENTORY` | `INITIAL_BALANCE`（正） | 库存减回 | 释放 `active_initial_inventory` 坑位 |
| `DAILY_SALES` | `SALE_OUT`（负）/ `SALE_RETURN`（正） | 库存加回 | 释放 `active_sales_date` 坑位；销售事实经视图自动剔除 |

### 4.3 撤销初始库存会让库存变负

初始库存打底，之后的销售批次已在此基础上扣减。反向冲平初始流水后余额为负。
规范 §12 明确「负库存允许存在，不能因库存不足丢弃真实销售」，故这是**可接受行为**，
不加拦截。但需在接口文档与响应说明中写清，避免被当成缺陷。

若要求先撤销后续销售批次才能撤销初始库存，则需引入批次依赖顺序检查——当前不做，
理由是规范未要求，且会让撤销从单批次动作变成需要遍历后继批次的图操作。

### 4.4 PENDING 商品不删

每日销售导入对未知条码会新建 `data_status = PENDING` 的商品。撤销时**保留**：

- 商品资料是全局共享的（`product` 无 `store_id`），其他门店的批次可能已引用同一条；
- 删除会撞 `fk_daily_product_sales_product` 等外键（原销售事实仍在）；
- 它们本就是等人补全的草稿，留着无害。

## 5. 接口契约

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/stores/{storeId}/import-batches` | 分页列表，可按 `importType` / `status` / `dataDateFrom` / `dataDateTo` 筛选 |
| `GET` | `/api/stores/{storeId}/import-batches/{batchId}` | 批次详情 + 行级错误明细 |
| `POST` | `/api/stores/{storeId}/import-batches/{batchId}/reverse` | 撤销，请求体 `{reversedBy, reversedReason}` |

撤销用 `POST .../reverse` 而非 `DELETE`：不删除资源，是受控状态流转加写新流水，
语义上是动作而非删除。规范 §13「任何入口都不得绕过状态规则直接更新批次状态」也要求
撤销必须走用例，不能表达成对资源的删除。

错误映射（沿用 `ApiExceptionHandler`）：

| 状况 | 状态码 |
|------|--------|
| 门店不存在 / 批次不存在或不属于该门店 | 404 |
| 批次状态非 `POSTED` | 409 |
| 撤销原因缺失或超长、分页参数非法 | 400 |

行级错误明细分页上限与商品查询保持一致，避免一次拉回一个批次的全部原始行
（真实销售文件 899 个数据行，失败批次可能整批都是错误行）。明细只返回
`parse_status` 为 `INVALID`/`WARNING` 的行，字段取 `row_number`、`barcode`、
`parse_status`、`error_message`，不返回 `raw_data`——那是审计用的整行 JSON，
放进列表响应体积大且对排错无增量价值。

**注意 `import_raw_row` 的外键只有 `batch_id`，没有 `store_id`**（其余批次相关表都是
`(batch_id, store_id)` 复合外键）。所以查原始行必须 `JOIN import_batch` 并在 join 条件上
带 `store_id`，不能只靠 `WHERE batch_id = ?` —— 否则 A 门店传 B 门店的 `batchId`
能读到别人的数据，直接违反 §9。批次详情查询同理，用 `uk_import_batch_id_store`。

## 6. 非目标

- 批次的部分撤销（只撤某几行）——全有或全无，与导入侧一致；
- 撤销后自动重传——撤销与导入是两次独立请求；
- 撤销的撤销（`REVERSED → POSTED`）——状态机只有单向 `POSTED → REVERSED`；
- 操作人身份认证——`reversedBy` 由请求提供，权限模型是路线图第 8 项；
- 批次列表页面与前端。

## 7. 测试计划

沿用三层约定：

- **应用层单测**（内存替身）：状态非 POSTED 拒绝、跨门店批次拒绝、门店不存在、
  撤销原因与操作人校验、分页与日期区间参数校验、筛选条件下传；
- **解析/无**：本切片不涉及文件解析；
- **真实 MySQL 集成测试**（`haowugou-bootstrap` 的 `integration` 包）：
  导入→撤销→库存归零、撤销初始库存后余额为负、撤销后同一文件同日重传成功（验证迁移生效）、
  撤销后销售指标经视图归零、二次撤销被拒、REVERSAL 流水的 `reversal_of_id` 与余额链正确、
  批次列表与详情的门店隔离（A 门店查不到 B 门店批次）；
- **MockMvc 契约测试**：三个接口的 JSON 契约（`JsonCompareMode.STRICT`）与 404/409/400 映射。

迁移脚本需要独立验证：在已有 `POSTED` 数据的库上执行 `ALTER TABLE` 不报错、
生成列回填正确、旧键删除后重复导入仍被挡。

## 8. 已确认决策与实施假设

已确认：**复用 `domain.importbatch` 包**，同包新增端口，不扩展 `ImportBatchRepository`（§3）。

以下按下述假设实施，实现前不再单独问；如有异议在设计评审阶段推翻，代码改动量都不大：

| # | 假设 | 依据 |
|---|------|------|
| 1 | 查询、撤销、同日重传放同一切片 | 路线图第 4 项三件事并列；同日重传就是迁移 + 撤销的直接结果，拆开会让第 4 项只能算部分完成 |
| 2 | 采纳 `active_file_hash` 迁移 | 不采纳则填错业务日期的场景只能手工改库（§2.2） |
| 3 | `FAILED` 与 `REVERSED` 都释放哈希坑位 | 失败批次无业务数据，与谓词 `status = 'POSTED'` 天然一致（§2.2） |
| 4 | `reversedBy` 与 `reversedReason` 都必填 | §18 要求撤销记录操作人、时间和原因；留空会让这两列在权限模型落地前毫无价值 |
| 5 | 撤销初始库存不拦负库存 | §12「负库存允许存在」（§4.3） |
| 6 | `PageResult` 搬到 `domain.pagination` | 避免 `domain.importbatch → domain.product`（§3.1） |

第 2 条是唯一带数据库变更的，会作为独立 SQL 提交并在集成测试里验证，不自动对任何环境执行。
