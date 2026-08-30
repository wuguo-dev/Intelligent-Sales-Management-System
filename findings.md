# 发现与决策

## 需求
- 复用 `GET /api/stores`，实现 `GET /api/stores/{storeId}/products`、`GET /api/stores/{storeId}/products/{productId}`、`GET /api/stores/{storeId}/warehouses`。
- 所有商品、库存、仓库和销售查询必须显式按 `storeId` 隔离。
- 支持指定门店内的关键字、仓库、品类、供应商、库存状态/范围、资料状态、日期范围和稳定分页。
- 日期指标仅统计当前门店有效 `POSTED` 批次；未传日期时指标不假定全历史。
- 门店或门店商品关系不存在返回 404，非法参数及跨门店仓库返回 400。
- 不删除或整体重构现有门店查询链路，不实现导入、扣库存、资料编辑或前端。
- 每个实施阶段完成后执行对应测试；需要修改已有代码时先向用户确认。

## 研究发现
- 项目是 Maven 多模块结构，包含 domain、application、infrastructure、bootstrap、common、agent。
- 当前目录没有 `.git`，无法执行文档建议的分支与 PR 流程。
- 已有代码主要是旧的门店、门店日销售和库存查询骨架。
- 数据库脚本文件名为 `database/好物购数据库建表.sql`，需继续核对实际结构。
- 任务文档与数据库脚本在本轮均已更新为多门店模型；本地脚本仍名为 `database/好物购数据库建表.sql`，而文档引用的是 `database/haowugou_schema.sql`。
- 已完整核对新版脚本：共 11 张表、2 个视图；库存视图和有效销售视图都包含 `store_id`，且销售视图按 `batch_id + store_id` 关联有效批次。
- `store_product_inventory` 主键为 `(store_id, product_id)`，仓库外键为 `(warehouse_id, store_id)`，数据库层可阻止库存关系引用其他门店仓库。
- 商品资料状态是全局属性；仓库位置和当前数量属于门店商品库存关系，且仓库允许为空。
- 现有 `StoreRepository`/`GET /api/stores` 与新版 `store` 表字段兼容，可以直接复用。
- 本机 `MySQL80` 服务正在运行且 `mysql` 客户端可用；用户已在本地配置中补充连接凭据，测试命令通过临时进程环境变量安全传递且不回显密码。
- IDEA 数据源元数据显示连接目标为本机 `haowugou`、用户 `root`；IDEA 使用 AWS Advanced JDBC Wrapper URL，但应用配置使用标准 MySQL JDBC URL，两者连接的是同一目标库。
- IDEA 的密码保险库不会自动注入 Maven/命令行进程；本轮通过读取本地应用配置并仅向子进程注入环境变量完成测试。
- 当前机器没有 Docker 命令，不能使用 MySQL Testcontainers 作为自动化替代。
- root 无密码命令行连接曾被 MySQL 正常拒绝；补充配置后已成功连接 MySQL 8.0.46 并验证目标表和视图。
- 新分页响应需要门店编码和名称，而现有 `StoreRepository` 只有全量列表与存在性判断；建议兼容性新增 `findActiveById`，需用户确认后修改既有接口、Adapter 和旧测试替身。
- 有效销售视图已在数据库层排除非 `POSTED` 批次，因此销售区间查询可直接聚合该视图。
- 旧 Java 查询仍访问数据库脚本未创建的 `store`、`store_daily_sales`、`inventory_snapshot`，但本阶段可新增独立商品查询链路而不修改旧代码。
- `bootstrap` 已有统一 Problem Detail 处理器和显式应用 Bean 组装模式；新增异常映射与新 Bean 可沿用该模式。
- 当前 Maven 配置没有基础设施模块测试依赖；是否新增测试依赖取决于基础设施验证策略。
- `ApiExceptionHandler` 的扩展只追加了新异常处理方法，未改动既有处理器；Spring 绑定异常（缺参数、类型不匹配）也已统一映射为 400 Problem Detail。
- MockMvc 契约测试采用 standalone 模式组装真实 `StoreProductQuery` 与内存 Repository 替身，只替换 I/O Adapter，无需 MySQL 或完整 Spring 上下文即可从 HTTP 边界验证契约。
- 根 pom 的全部 6 个模块都是 bootstrap 的依赖，`mvn -pl haowugou-bootstrap -am test` 的覆盖范围与根目录 `mvn test` 完全一致。
- 集成测试测试数据使用 `8e18` 以上的高位 ID 与 `IT-` 前缀门店编码；回归后按高位 ID 与编码前缀查询残留均为 0，确认回滚生效。

## 技术决策
| 决策 | 理由 |
|------|------|
| 优先新增商品查询垂直切片 | 避免触碰旧链路，满足最小改动原则 |
| Controller 只依赖 application 用例 | 遵守任务文档给出的模块职责 |
| 使用 `BigDecimal` 与 `LocalDate` | 数量/金额及业务日期需要精确、稳定的类型 |
| 新商品查询不复用旧 `InventoryItem` 模型，但复用 `Store`/`StoreRepository` | 新库存语义是门店商品当前库存；门店主数据语义与现有模型一致 |
| REST 使用门店嵌套路径 | 显式表达门店边界，避免调用方漏传 `storeId` |
| 不新增未出现在 HTTP 契约中的“近 7/30 天”参数 | 文档只定义 `startDate/endDate`；快捷日期可由调用方转换 |
| `StoreRepository.findActiveById` 提供兼容默认实现，MyBatis Adapter 覆盖为精确查询 | 不破坏现有测试替身，同时避免生产查询扫描全部门店 |
| 应用层先完成所有纯参数校验，再访问门店或商品 Repository | 非法请求不会触发数据库访问，错误边界明确 |
| 仓库过滤在商品查询前验证 `(storeId, warehouseId)` | 跨门店仓库参数返回明确 400，且不会进入商品查询 |
| 商品分页固定使用“总数、当前页、供应商批量、可选销售批量”最多四次查询 | 查询次数不随当前页商品数量增长，消除 N+1 |
| 供应商筛选使用 `EXISTS`，供应商名称单独按当前页商品批量查询 | 防止多对多 JOIN 造成重复商品和分页总数错误 |
| 销售聚合只查询 `v_posted_daily_product_sales`，同时限制 `store_id`、商品 ID 集和闭区间日期 | 数据库视图排除撤销批次，查询条件防止跨店及跨页混入 |
| 真实 MySQL 集成测试使用事务内高位 ID 数据并直接回滚 JDBC Connection | 可验证 MySQL 方言、视图和索引，同时不保留测试数据 |
| 契约测试使用 JSON `STRICT` 比较与真实应用用例 | 字段增删会立即暴露，防止响应契约漂移 |
| 数据库密码从 `application-local.yml` 提取后仅注入子进程环境变量 | 命令输出与日志不回显凭据 |

## 遇到的问题
| 问题 | 解决方案 |
|------|---------|
| 项目不是 Git 仓库 | 不进行 Git 操作，交付时明确说明 |
| 文档中的 SQL 文件名与实际文件名不同 | 以实际同步更新后的 `database/好物购数据库建表.sql` 为实现依据，并在交付时指出 |
| IDEA 数据源凭据无法由 Maven 自动复用 | 用户补充本地应用配置后，测试脚本安全读取并临时注入子进程环境变量 |
| 首轮测试数据未自动回滚 | 已删除本轮生成的 18 个测试门店及全部关联测试数据；改为直接回滚 JDBC Connection，连续验证残留为 0 |
| 中断遗留：契约测试缺少 `nullValue` 静态导入导致编译失败 | 补充 hamcrest 静态导入后编译通过，13/13 契约测试通过 |

## 资源
- 任务文档：`C:\Users\xdj\Desktop\商品查询接口开发任务.md`
- 项目：`D:\Dev\Code\Project\Intelligent-Sales-Management-System-main`

## 视觉/浏览器发现
- 本任务尚未使用视觉或浏览器资料。

---

# 初始库存导入（2026-08-27）

## 需求
- 路线图第 1 项「按门店导入初始库存」；核心目标：验证真实 POS 导出的 Excel（.xls）能否成功导入 MySQL。
- 只做导入接口（批次查询、撤销、日销售导入属后续路线）。
- 用户两段式工作流：导入时没有仓库列，导入后再在商品编辑页面分配仓库。
- 严格模式：任何行级错误（未知条码、负数量、重复条码等）→ 整批 FAILED，全有或全无。

## 研究发现
- 真实 POS 文件 12 列全部为文本单元格，表头第 1 行，末尾有空行，无仓库列；条码含前导零，
  必须按文本读取（防科学计数法）。
- `store_product_inventory.warehouse_id` 允许 NULL（注释「待分配时可为空」），两段式工作流零改表。
- `import_batch` 用生成列 + 唯一索引在库层保证：同店同类型同 SHA-256 唯一、每店最多一个
  有效初始库存批次——应用预检给出友好 409，数据库约束兜底。
- `inventory_movement` 有 `balance_after = balance_before + quantity_change` CHECK，
  流水必须「先 SELECT 现有余额 → Java 计算 → 写入」。
- `import_raw_row.row_number` 是 MySQL 8 保留字（窗口函数），SQL 必须反引号。
- MySQL 8.0.19+ upsert 用行别名 `AS new` 取代已废弃的 `VALUES()`；右侧引用现有值需表名限定避免歧义。
- MySQL JSON 列会规范化存储（如冒号后加空格），断言落库内容时需考虑存储形式。
- 非 Excel 字节被 EasyExcel 按 CSV 兜底解析，可能不抛解析异常而是得到缺失表头错误。
- 全链路集成测试放在 bootstrap 模块：它是唯一组装点，同时可见 application 与 infrastructure，
  避免给 infrastructure 引入对 application 的测试依赖。

## 技术决策
| 决策 | 理由 |
|------|------|
| 方案 A：上传即同步校验过账，批次直接落终态 | 校验与过账同一事务，全有或全无；VALIDATING/POSTING 仅为瞬态 |
| 行级错误整批 FAILED 留审计，未知条码同样拒绝 | 初始库存宁缺勿错；与架构规范 §17.2「未知条码入账待完善」的差异已记入设计文档，保留给日销售导入 |
| 按表头名称定位「条码」「库存数量」列 | 不依赖列序，POS 改版更稳健；其余 10 列入原始行审计 JSON |
| 数量 0 行跳过、数量 >0 行才建库存与流水 | POS 会导出零库存商品，不算错误 |
| 冲突行只累加数量与版本号，不覆盖仓库分配 | 保护后续编辑页面分配的仓库 |
| 可选 `warehouseId` 请求参数；不传则 NULL | 与两段式工作流一致；跨门店仓库返回 400 |
| 应用层注入 `Supplier<LocalDate>` | 数据归属日期可测试（固定日期），生产为导入当天 |
| SHA-256 文件指纹 + 数据库唯一键双重查重 | 按内容而非文件名判重；并发下唯一键兜底 |

## 遇到的问题
| 问题 | 解决方案 |
|------|---------|
| EasyExcel 对非 Excel 内容不抛解析异常（当 CSV 解析） | 测试只断言 `ImportFileFormatException` 类型，不绑定消息 |
| MockMvc `.param()` 链式调用丢失 multipart 构建器类型 | 先 `.file()` 再 `.param()` |
| `row_number` 保留字导致插入/查询语法错误 | SQL 与测试 JDBC 查询统一反引号 |
| upsert 行别名下右侧列歧义 | 表名限定 `store_product_inventory.current_quantity` |
| JDBC 参数中的中文 JSON 路径解析失败 | 改为读出 raw_data 后在 Java 侧断言 |
| DECIMAL(18,3) 标度差异（0 vs 0.000） | 期望值按库内标度书写 |

## 资源
- 设计文档：`docs/superpowers/specs/2026-08-27-initial-inventory-import-design.md`
- 真实 POS 文件：`C:\Users\xdj\Desktop\商品资料1.xls`（冒烟测试输入）
- 架构规范：`好物购项目整体架构规范.md`

---

# 每日销售导入与库存扣减（2026-08-30）

## 需求
- 路线图第 3 项：按门店导入每日销售数据并扣减库存，核心目标是真实 POS《商品销售汇总》能入账。
- 与上一片的关键差异：未知条码**不再整批拒绝**，而是自动建 PENDING 待完善商品后照常入账
  （架构规范 §17.2；销售事实必须入账，否则销售额口径失真）。

## 研究发现
- 真实文件 901 行 × 15 列全文本：表头 1 行 + 899 个数据行 + 1 个合计行（条码与商品名皆空）。
- 899 行中 413 行「数量与收入同时为 0」——POS 把当期未销售商品一并导出；只留原始行审计，
  不写销售事实、不产生流水，也不建待完善商品。跳过条件必须取「两者同时为 0」：实测
  「数量 0 但收入非 0」与其反向各 0 行，任一单条件都会误伤。
- 第 4 列是「**当前**机构最后进价」而非销售当时成本：45 行「收入 − 数量 × 进价」与 POS 毛利率矛盾。
  故毛利额只能取 `收入 × POS 毛利率 ÷ 100`，进价仅作待完善商品初值提示。
- 文件无日期列（本期/同期区间由操作员在 POS 侧选择）→ `businessDate` 只能是必填请求参数，
  且 `import_batch.data_date` 与 `uk_import_batch_active_sales_date` 都要求有值。
- 毛利率空白与 0% 语义不同（前者是 POS 未报），因此缺省为 null 而非 0；数量与收入空白按 0 处理，
  这类行本就不构成销售事实，报错只会把整批卡住。
- 同条码的多个**未识别**供应商必然归并为一条事实：`supplier_key = IFNULL(supplier_id, 0)` 决定了
  库层放不下两行。真实文件里正好命中一次（486 个有销售的行 → 485 条事实），归并后数量与收入求和，
  与文件自带合计行仍逐项一致。
- 销售事实按 `(商品, 供应商)` 落库，库存流水按**商品**汇总：前者匹配唯一键，后者保证每商品每批次
  一条流水且余额可链式追溯（`chk_inventory_movement_balance`）。

## 技术决策
| 决策 | 理由 |
|------|------|
| 新建独立端口 `DailySalesFileParser`/`DailySalesImportRepository` | 行模型与写入表都不同；给 `ImportFileParser` 加第二个实现会让既有 `InitialInventoryImportConfiguration` 按类型注入变成歧义注入 |
| 不改动初始库存导入切片任何文件 | 两片 SQL 各自独立，不引入无替换需求的抽象 |
| 未知条码建 PENDING 商品，品类/供应商按名称匹配、匹配不到记 NULL 且不自动创建 | 避免销售文件污染主数据；商品本就是待完善 |
| 不写 `product_supplier` 关联 | 供应商关联属商品主数据维护范围 |
| 净销量 > 0 → SALE_OUT，< 0 → SALE_RETURN，= 0 → 无流水 | `chk_inventory_movement_nonzero` 禁止 0 变化量 |
| 库存行不存在也插入，允许负库存 | 架构规范 §17.2；销售事实不能因缺库存行而丢 |
| 归并行 `reported_gross_profit_rate` 记 NULL | 该列语义是 POS 原始值供核对，归并后无法归属单一原始值 |
| 真实文件测试改为读环境变量 `HAOWUGOU_POS_SALES_FILE`，未设置则跳过 | 真实业务文件不进仓库，且不能让缺文件的机器构建失败 |

## 遇到的问题
| 问题 | 解决方案 |
|------|---------|
| 集成测试 `supplierId` 断言得 0 而非 null | `ResultSet.wasNull()` 只反映最近一次读取，写在构造参数列表里会被后续 getter 重置；紧跟 `getLong` 先存下可空值 |
| 真实文件事实数比「非全零行数」少 1 | 非缺陷，是未识别供应商归并的既定行为；断言改为按落库供应商复算期望值 |
| 期望的双供应商事实顺序反了 | `ORDER BY product_id, supplier_key` 下无供应商行的 key 为 0，排在真实供应商 id 之前 |
| `-Dtest=X` 报 No tests matching pattern | 加 `-Dsurefire.failIfNoSpecifiedTests=false`；`-DfailIfNoTests=false` 无效 |

## 资源
- 设计文档：`docs/superpowers/specs/2026-08-30-daily-sales-import-design.md`
- 真实 POS 文件：`C:\Users\xdj\Desktop\商品销售汇总.xls`（端到端测试输入，经环境变量传入）
