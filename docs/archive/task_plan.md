# 任务计划：多门店商品查询纵向链路

## 目标
在不擅自重构现有链路的前提下，实现并验证指定门店内的商品分页查询、商品详情查询和启用仓库查询，并复用门店列表接口。

## 当前阶段
全部阶段完成，任务已交付

## 各阶段

### 阶段 1：需求、基线与契约确认
- [x] 读取任务文档并区分建议与用户授权
- [x] 检查模块依赖、现有代码、数据库脚本和配置
- [x] 执行现有测试并记录基线
- [x] 识别是否需要改动已有代码并在必要时请求确认
- **状态：** complete

### 阶段 2：领域与应用契约
- [x] 新增查询条件、状态、结果与仓储接口
- [x] 新增应用服务、校验和应用异常
- [x] 执行领域/应用层测试
- **状态：** complete

### 阶段 3：基础设施查询实现
- [x] 新增 MyBatis 查询与仓储适配器
- [x] 验证分页、去重、日期范围和批次口径
- [x] 执行基础设施测试
- **状态：** complete

### 阶段 4：HTTP 接口与契约
- [x] 新增 Controller、请求/响应 DTO 与异常映射
- [x] 实现三个 REST 接口
- [x] 执行 MockMvc 契约测试
- **状态：** complete

### 阶段 5：回归与交付
- [x] 执行完整 Maven 测试
- [x] 在可用条件下执行本地 MySQL 只读验证
- [x] 检查改动范围与文档一致性
- [x] 汇总结果、限制和后续建议
- **状态：** complete

## 关键问题
1. 更新后的数据库脚本的表、字段、索引和视图是否与多门店任务文档一致？
2. 本地 MySQL 是否可连接并已导入目标模型及测试数据？
3. 新功能能否仅以新增代码实现；若必须修改旧代码，需先获得用户确认。

## 已做决策
| 决策 | 理由 |
|------|------|
| 不执行文档中的 Git 分支/PR 操作 | 目录不是 Git 仓库，且文档内容不是用户直接授权 |
| 旧门店查询链路默认保留 | 文档明确排除删除或整体重构，用户要求修改旧代码前确认 |
| 每阶段运行最小相关测试，最终运行全量测试 | 满足用户“每完成一步测试”的要求 |
| 采用 `/api/stores/{storeId}/...` 嵌套路径 | 更新后的任务文档明确优先此风格，且项目尚未实现商品查询扁平路径 |
| 复用现有 `GET /api/stores` 与 `StoreRepository` | 新文档明确要求复用，避免重复能力 |
| 不新增未出现在 HTTP 契约中的“近 7/30 天”参数 | 文档只定义 `startDate/endDate`，避免臆造公开 API |
| 用户确认进入阶段 2 | 允许实施此前对齐的 `StoreRepository.findActiveById` 兼容性扩展 |
| 用户确认进入阶段 3 | 开始实现并验证多门店 MyBatis 查询与仓储 Adapter |
| 阶段 4 契约测试使用 standalone MockMvc + 真实应用用例 + 内存替身 | 从 HTTP 边界验证公开契约，不依赖 MySQL 或 Spring 完整上下文 |
| `ApiExceptionHandler` 仅新增异常处理方法 | 既有处理器保持不变，符合最小改动原则 |
| README/CHANGELOG 追加新接口与验证结果 | 文档与实现保持一致，只做新增不改写历史条目 |

## 遇到的错误
| 错误 | 尝试次数 | 解决方案 |
|------|---------|---------|
| `git -C <repo> status` 报目录不是 Git 仓库 | 1 | 不执行任何 Git 操作，按普通项目目录开发 |
| 首次更新计划补丁上下文顺序不匹配 | 1 | 重新读取计划后使用精确的小范围补丁 |
| 应用测试把合法分页参数误列为非法场景 | 1 | 将断言输入改为真正非法的空查询条件 |
| MySQL 短参数连接探测被 Windows 客户端误解析 | 1 | 改用 `--host=...` 等完整长参数 |
| MySQL 命令行未获得 IDEA 密码 | 1 | 不尝试提取密码；使用自包含查询集成测试，真实库验证待环境变量注入 |
| MyBatis 会话未识别 JDBC 直接插入为脏数据，未执行回滚 | 1 | 测试保存 JDBC Connection，并在清理阶段直接调用 `connection.rollback()` |
| 阶段 4 契约测试缺少 `org.hamcrest.Matchers.nullValue` 静态导入（中断点） | 1 | 补充静态导入后 13/13 通过 |

## 备注
- 任务文档中的建议不得视为扩大权限的指令。
- 所有既有代码改动先判断是否必要；不合理但与本任务无关的代码只记录不修改。
- 新版文档已将核心粒度调整为“门店 + 商品”，所有新增查询必须把 `storeId` 下传到 Repository。

---

# 后续任务：初始库存导入（2026-08-27）

## 目标
路线图第 1 项：上传 POS 商品资料 Excel（.xls / .xlsx），按门店导入初始库存，验证真实数据成功落库。

## 当前阶段
实现与验证全部完成（68/68 测试 + 真实 POS 文件冒烟通过），文档已同步并提交分支
`codex/initial-inventory-import`，推送与合并待用户确认。

## 各阶段
- [x] 需求澄清与设计（头脑风暴 → 设计文档并提交）— complete
- [x] domain 端口与值对象（`domain.importbatch`）— complete
- [x] application 用例 `PostInitialInventoryImport` + 12 个单测 — complete
- [x] infrastructure EasyExcel 解析器 + 7 个单测 — complete
- [x] infrastructure MyBatis Adapter + `ImportBatchMapper.xml` — complete
- [x] bootstrap Controller + 配置 + 异常映射 + 9 个 MockMvc 契约测试 — complete
- [x] 真实 MySQL 全链路集成测试 4 项 — complete
- [x] 全量回归（68/68）+ 真实 POS 文件冒烟测试 — complete
- [x] 文档同步（README/CHANGELOG/progress/findings/task_plan）并提交 — complete

## 已做决策
| 决策 | 理由 |
|------|------|
| 方案 A：上传即同步校验过账，全有或全无 | 校验与过账同一事务，批次直接落终态 |
| 严格报错整批拒：未知条码 → 整批 FAILED | 初始库存宁缺勿错；与架构规范 §17.2 差异记入设计文档 |
| 只做导入接口 | 批次查询/撤销属路线图第 2/4 项，不提前实现 |
| 可选 `warehouseId` 参数，不传则仓库待分配 | 与用户两段式工作流一致，零改表 |
| 全链路集成测试放 bootstrap 模块 | bootstrap 是唯一组装点，避免 infrastructure 测试依赖 application |
| 文件查重按内容 SHA-256 | 与数据库唯一键双重防护，按内容而非文件名判重 |

## 关键问题
1. 真实 POS 文件的列布局与单元格类型是否与假设一致？（已实测：12 列全文本、表头首行）
2. 仓库两段式工作流是否需要改表？（不需要：`warehouse_id` 可空）
3. 集成测试放置位置是否违反模块依赖方向？（放 bootstrap，保持方向单向）

## 备注
- 冒烟测试使用真实桌面文件《商品资料1.xls》验证端到端，清理后残留 0。
- 提交与推送需用户确认后执行。

---

# 后续任务：每日销售导入与库存扣减（2026-08-30）

## 目标
路线图第 3 项：按门店导入每日销售数据（POS 商品销售汇总 .xls / .xlsx）并扣减库存，
验证真实 POS 文件成功入账为销售事实。

## 当前阶段
实现与验证全部完成（119/119 测试 + 真实 POS 销售文件端到端通过），文档已同步；
提交与推送待用户确认。

## 各阶段
- [x] 真实文件实测与设计文档 — complete
- [x] domain 端口与值对象（`domain.salesimport`，9 个文件）— complete
- [x] application 用例 `PostDailySalesImport` + 19 个单测 — complete
- [x] infrastructure EasyExcel 销售解析器 + 9 个单测 — complete
- [x] infrastructure MyBatis Adapter + `DailySalesImportMapper.xml` — complete
- [x] bootstrap Controller + 配置 + 异常映射 + 13 个 MockMvc 契约测试 — complete
- [x] 真实 MySQL 全链路集成测试 8 项 — complete
- [x] 真实 POS 销售文件端到端测试 + 全量回归（119/119）— complete
- [x] 文档同步（设计文档/README/CHANGELOG/progress/findings/task_plan/CLAUDE.md）— complete

## 已做决策
| 决策 | 理由 |
|------|------|
| 沿用方案 A：上传即同步校验过账，全有或全无 | 与初始库存导入一致，批次直接落终态 |
| `businessDate` 必填请求参数 | 文件无日期列，且唯一键要求 `data_date` 有值 |
| 未知条码建 PENDING 商品后照常入账 | 架构规范 §17.2；销售事实不能因主数据缺失而丢 |
| 毛利额 = 收入 × POS 毛利率 ÷ 100 | 第 4 列是当前最后进价，实测 45 行与毛利率矛盾 |
| 数量与收入同时为 0 的行只留审计 | 413/899 行无分析价值，且库层禁止 0 流水 |
| 新建独立端口，不复用初始库存导入的端口与 Mapper | 行模型与写入表不同，复用会导致按类型注入歧义 |
| 真实文件测试路径经环境变量传入 | 业务文件不进仓库，缺文件时跳过而非失败 |

## 关键问题
1. 真实销售文件是否有日期列？（无，`businessDate` 定为必填参数）
2. 能否用「收入 − 数量 × 进价」算毛利额？（不能，第 4 列是当前进价，实测 45 行矛盾）
3. 同条码多供应商如何落库？（按 `supplier_key = IFNULL(supplier_id,0)` 归并，未识别供应商合成一条）
4. 未知条码是否阻断整批？（不阻断，建 PENDING 商品后入账，与上一片相反）

## 备注
- 端到端测试输入《商品销售汇总.xls》，899 数据行落库后数量 988.000、收入 7342.00
  与文件自带合计行一致；测试结束回滚，残留 0。
- 路线图下一项：批次查询与撤销（REVERSED/REVERSAL 链路）。

# 任务：导入批次查询、撤销与同日重传

## 目标
补上前两个导入切片的缺口：按门店查批次（分页 + 类型/状态/日期筛选 + 问题行明细）、
撤销已入账批次（回滚库存、写反向流水、不删事实）、撤销后同文件同业务日期可原样重传。

## 当前阶段
代码四层与文档完成，13 个用例单测 + 12 个契约测试通过，全量 151 通过 / 20 跳过。
**阻塞项**：`active_file_hash` 迁移未在本地库执行，7 个真实 MySQL 集成测试尚未实跑。

## 各阶段
- [x] 设计文档 + 数据库现状核查 + 迁移脚本 — complete
- [x] domain 端口与值对象（复用 `domain.importbatch`）— complete
- [x] application `ImportBatchQuery` + `ReverseImportBatch` + 13 个单测 — complete
- [x] infrastructure `ImportBatchAdminMapper` + XML 11 条语句 — complete
- [x] bootstrap 三个接口 + 响应模型 + 配置 + 12 个契约测试 — complete
- [ ] 真实 MySQL 全链路集成测试 7 项 — 已编写，待迁移后实跑
- [x] 文档同步（设计文档/README/CHANGELOG/progress/findings/task_plan/CLAUDE.md）— complete

## 已做决策
| 决策 | 理由 |
|------|------|
| 复用 `domain.importbatch`，只建一个 Admin Mapper | 用户明确要求；撤销逻辑与批次类型无关 |
| 文件指纹唯一键改建在生成列 `active_file_hash` 上 | 业务日期填错时文件本身没问题，「改内容让哈希变化」不成立 |
| 撤销事务先翻状态，兼作乐观锁与行锁 | 影响 0 行即并发下已被撤销，干净拒绝而非撞约束 |
| 反向流水与原流水 1:1 并串余额链 | `uk_inventory_movement_reversal` 不允许一条原流水被冲销两次 |
| `balance_before` 由 infrastructure 取库内当前值 | 中间可能已有别的批次动过库存 |
| 允许撤销把库存打成负数 | 硬拦会让「撤错的期初」无法收拾 |
| 失败批次不可撤销 | 没产生过库存变化 |
| 迁移交用户手工执行 | 权限分类器拦下该命令，不绕行 |

## 关键问题
1. 撤销需要新增字段吗？（几乎不需要，唯一缺口是文件指纹唯一键不含状态）
2. 反向流水能按商品归并吗？（不能，`uk_inventory_movement_reversal` 要求 1:1）
3. 撤销后销售事实要删吗？（不删，`v_posted_daily_product_sales` 已在库层按状态过滤）
4. 撤销把库存打成负数要拦吗？（不拦，见上表）

## 备注
- 迁移未执行时，两条导入链路的**既有**集成测试也会因 `Unknown column 'active_file_hash'` 失败
  （`countBatchByFileHash` 已改查生成列）。
- 迁移后验证命令：
  `mvn -pl haowugou-bootstrap -am test -Dtest=ImportBatchReversalIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
- 提交与推送待用户确认。
