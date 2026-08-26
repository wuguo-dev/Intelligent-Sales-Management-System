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

### 调整

- 将 Controller 内嵌 Response 拆分为独立顶层类型，按经营数据功能包组织；
- 将 `DailySalesResponse` 明确命名为 `StoreDailySalesResponse`，为商品日销售扩展预留语义；
- Controller 仅负责 HTTP 适配，业务校验集中在应用层；
- 使用专用查询参数异常，避免把未知编程错误错误映射为 HTTP 400；
- 查询参数在访问 Repository 前完成校验；
- `StoreRepository` 兼容性新增 `findActiveById` 默认实现，MyBatis Adapter 覆盖为按主键精确查询；
- `ApiExceptionHandler` 仅追加新异常与 Spring 绑定异常映射，既有处理器保持不变。

### 安全

- 数据库密码和 DashScope API Key 改为环境变量读取，不进入源码或 Git 历史。

### 验证

- `mvn test` 通过：应用层 3 个测试、Controller 7 个测试，共 10 个测试；
- 保持三个既有接口的 URL 和成功响应 JSON 字段不变；
- 多门店商品查询全量回归：36/36 测试通过（应用层 10、真实 MySQL 集成 6、启动模块 20），
  测试数据残留 0；
- 三个既有接口与 `GET /api/stores` 复用未受影响。
