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
