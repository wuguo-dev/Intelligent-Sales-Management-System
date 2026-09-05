# 前端导入切片设计（批次管理 + 上传全链路）

- 状态：已实现 —— 10 个任务完成，69 个前端单测 + 生产构建通过；经真实后端完成 API 级闭环验证
  （销售导入 899 行 POSTED → 批次列表 → 详情 → 撤销 REVERSED → 同文件同日期重传成功 →
  初始库存 FAILED 行级错误），前端页面级验证由 69 个 RTL 单测覆盖
- 已知后端问题（非本切片）：`SecurityConfiguration` 未放行 `/error`，框架级错误
  （未知路由 404/405/请求体解析失败）的错误分发被 `denyAll()` 拦截，全部掩盖为「403 权限不足」。
  前端不受影响（axios 请求体合法 UTF-8、业务异常走 ApiExceptionHandler），
  待单独修：加 `.requestMatchers("/error").permitAll()` 并补 SecurityRulesTest 用例
- 功能切片：`codex/import-batch-reversal`（沿用当前分支）
- 前序切片：`docs/design/2026-09-01-frontend-auth-skeleton-design.md`（认证骨架，已实现）
- 对应后端：`ImportBatchController`、`InitialInventoryImportController`、`DailySalesImportController`（全部管理员专用）
- 技术栈：沿用认证骨架——React 18/19 + TS strict + Vite + antd（当前为 v6）+ react-router-dom + zustand + axios + vitest

## 1. 业务目标

导入链路的前端闭环：**上传 → 查批次 → 看详情与问题行 → 撤销 → 重传**。三件事：

1. **导入上传**：初始库存导入（可选仓库）与每日销售导入（必填业务日期），结果按终态展示（POSTED 行数统计 / FAILED 行级错误表）；
2. **批次查询**：按门店列出导入批次（类型/状态/数据日期筛选 + 分页），点开详情看元信息与问题行；
3. **撤销**：POSTED 批次可撤销（操作人 + 原因必填），撤销后同一文件可重传。

配套落地两个公共设施（先前已定方向）：手写侧边菜单、顶栏全局门店选择器。

## 2. 后端接口契约

全部在 `/api/stores/{storeId}` 下，`SecurityConfiguration` 已限 `hasRole(ADMIN)`；普通用户 403，前端按 `canManage` 隐藏入口（双保险）。

| 接口 | 请求 | 响应要点 |
|------|------|----------|
| `GET /import-batches` | 筛选：`importType`（INITIAL_INVENTORY/DAILY_SALES）、`status`（VALIDATING/POSTING/POSTED/REVERSED/FAILED）、`dataDateFrom`/`dataDateTo`；分页 `page`（**0 起**）、`size`（默认 20） | `ImportBatchPageResponse{store, items[], page, size, totalElements, totalPages}` |
| `GET /import-batches/{batchId}` | 问题行分页参数同上 | `ImportBatchDetailResponse{store, batch, problemRows{items[], page, size, totalElements, totalPages}}` |
| `POST /import-batches/{batchId}/reverse` | `{reversedBy, reversedReason}` 均必填 | 200 即 REVERSED；404 非本店；409 状态非 POSTED；400 缺字段 |
| `POST /inventory/import` | multipart：`file`（.xls/.xlsx）+ 可选 `warehouseId` | `{batchId, status(POSTED/FAILED), totalRows, successRows, errorRows, errors[≤50]{rowNumber, barcode, message}}`；400 文件级错误/404 门店/409 重复文件或已有有效初始批次 |
| `POST /sales/import` | multipart：`file` + 必填 `businessDate` | 同上 + `salesRows, pendingProductsCreated, deductedProducts`；409 重复文件或该日已有有效销售批次 |
| `GET /warehouses` | — | `WarehouseResponse[]`（已启用仓库，上传页 warehouseId 下拉数据源） |
| `GET /api/stores` | —（管理员专用） | `StoreResponse[]{id, storeCode, storeName}`（门店选择器数据源） |

### 2.1 关键业务口径（前端必须照此渲染与提示）

- 导入是**同步全有或全无**：响应 200 但 `status=FAILED` 表示行级错误整批未入账——前端必须区分「请求成功」与「批次成功」，FAILED 展示错误表而不是成功提示；
- **撤销释放三个坑位**（有效初始批次/有效销售日期/文件指纹），撤销后同一文件同一日期可原样重传——撤销成功提示里带上「可重新上传同一文件」；
- 409 的场景语义各不相同（重复文件/已有有效批次/不可撤销），前端一律展示后端 Problem Detail 的 `detail` 文案，不自行翻译；
- 初始库存 FAILED 错误行定位 Excel 原始行号 `rowNumber`；每日销售同样；
- 撤销允许库存变负（撤初始库存而后续销售已扣过）——不做前端库存校验提示。

### 2.2 统一错误体

沿用认证骨架的 `ProblemDetail{type?, title?, status, detail?}`；409 时 `detail` 是用户可行动的文案（如「该业务日期已存在有效销售批次，先撤销再导入」）。

## 3. 路由与外壳

```
/                                    首页（现有，保留）
/stores/:storeId/imports/inventory   初始库存导入页
/stores/:storeId/imports/sales       每日销售导入页
/stores/:storeId/import-batches      导入批次列表页
```

- **MainLayout 重构**：`Layout.Sider` 侧边菜单（分组「导入管理」：初始库存导入 / 每日销售导入 / 导入批次），当前菜单项高亮跟随路由；Header 左 Logo + 标题、中间门店选择器（仅管理员，未选显示「未选门店」）、右侧现有用户下拉；
- **菜单按 `canManage` 过滤**：普通用户看不到导入组；直接访问 URL 由后端 403 兜底（前端路由守卫不做角色判断，避免与后端口径走偏）；
- **门店选择器**：切换时把当前 URL 的 `:storeId` 段替换为新值后 `navigate`（工具函数 `swapStoreIdInPath(pathname, newId)`，落在 `router/` 下并单测）；首页无 storeId 段时跳到「导入批次」默认业务页；
- **普通用户**：URL 中 storeId 与 `profile.store.id` 不符 → 重定向到自家门店同路径；无门店选择器；
- **管理员未选门店**：业务页渲染「请先选择门店」空状态（`Empty` + 引导文案），不做跳转——选择器就在顶栏。

## 4. 状态与 API 层

### 4.1 api/stores.ts

- `listStores(): Promise<StoreView[]>`——`GET /api/stores`（复用 `types.ts` 的 `StoreView`）。

### 4.2 stores/app.ts（zustand）

```ts
interface AppState {
  stores: StoreView[];                    // 门店列表（仅管理员加载）
  currentStoreId: number | null;          // 管理员选中；普通用户恒为 null（从 profile 派生）
  loadStores(): Promise<void>;            // 仅管理员调用，失败静默置空（选择器展示空数据）
  selectStore(id: number): void;
  clearStore(): void;                     // 登出时清空
}
```

普通用户的「当前门店」在组件里从 `profile.store.id` 派生，**不写入该 store**——两套来源并存会出「选 A 实际用 B」的状态分叉。

### 4.3 api/imports.ts（类型与后端 1:1）

```ts
export type ImportType = 'INITIAL_INVENTORY' | 'DAILY_SALES';
export type ImportBatchStatus = 'VALIDATING' | 'POSTING' | 'POSTED' | 'REVERSED' | 'FAILED';

export interface ImportBatchItem {
  batchId: number; importType: ImportType; status: ImportBatchStatus;
  dataDate: string | null; fileName: string;
  totalRows: number; successRows: number; errorRows: number;
  importedAt: string; postedAt: string | null; reversedAt: string | null;
  reversible: boolean;
}
export interface ImportBatchPage {
  store: StoreView; items: ImportBatchItem[];
  page: number; size: number; totalElements: number; totalPages: number;
}
export interface ImportBatchProblemRow {
  rowNumber: number; barcode: string | null; parseStatus: string; errorMessage: string;
}
export interface ImportBatchDetail {
  store: StoreView;
  batch: { batchId; importType; status; dataDate; fileName; fileHash; totalRows; successRows;
           errorRows; errorMessage; operatorName; importedAt; postedAt; reversedAt;
           reversedBy; reversedReason; reversible };
  problemRows: { items: ImportBatchProblemRow[]; page; size; totalElements; totalPages };
}
export interface WarehouseView {   // 与后端 WarehouseResponse 1:1
  id: number; storeId: number; warehouseCode: string | null; warehouseName: string;
}
export interface RowError { rowNumber: number; barcode: string | null; message: string; }
export interface ImportResult {           // inventory 与 sales 共用的成功/失败体
  batchId: number; status: 'POSTED' | 'FAILED';
  totalRows: number; successRows: number; errorRows: number;
  salesRows?: number; pendingProductsCreated?: number; deductedProducts?: number;
  errors: RowError[];
}
export interface ReverseResult {
  store: StoreView; batchId: number; importType: ImportType; dataDate: string | null;
  fileName: string; reversedMovements: number; restoredProducts: number;
  reversedAt: string; reversedBy: string; reversedReason: string;
}
```

函数签名（查询参数序列化与 FormData 构建收在 api 层，页面不碰 axios）：

```ts
listBatches(storeId, criteria: { importType?; status?; dataDateFrom?; dataDateTo?; page; size }): Promise<ImportBatchPage>
getBatch(storeId, batchId, page, size): Promise<ImportBatchDetail>
reverseBatch(storeId, batchId, body: { reversedBy: string; reversedReason: string }): Promise<ReverseResult>
importInventory(storeId, file: File, warehouseId?: number): Promise<ImportResult>
importDailySales(storeId, file: File, businessDate: string): Promise<ImportResult>
listWarehouses(storeId): Promise<WarehouseView[]>
```

`importInventory`/`importDailySales` 用 `FormData`（`file` + 参数），不手工设 Content-Type（axios 自动带 boundary）；CSRF 拦截器已全局生效。

## 5. 页面设计

### 5.1 ImportBatchesPage（批次列表）

- 筛选栏 `Form` inline：类型 Select、状态 Select、数据日期 RangePicker、查询/重置；查询把筛选写入组件状态 → 重新请求（page 归 0）；
- `Table`：列 = 批次ID / 类型 Tag / 状态 Tag（POSTED 绿、REVERSED 灰、FAILED 红、其余蓝）/ 数据日期 / 文件名（省略号）/ 行数 `成功/总数`（FAILED 显示错误数红字）/ 上传时间 / 撤销时间 / 操作「详情」；
- 分页：antd `pagination` 用 `current = page + 1`、`pageSize = size`、`total = totalElements`，onChange 转回 0 基页码；
- 行点击 → 详情抽屉；数据加载中 Table loading。

### 5.2 BatchDetailDrawer（详情 + 问题行 + 撤销）

- `Drawer` 宽约 720：`Descriptions` 元信息（fileHash 用 Typography.Text copyable 且 ellipsis 截断；errorMessage 有值才显示；reversed 三字段仅 REVERSED 显示）；
- 问题行内嵌 `Table`（行号/条码/parseStatus Tag/错误信息），自带分页，独立请求 `getBatch`；
- **撤销流程**：`reversible` 才渲染「撤销批次」按钮（danger）→ `Modal` 表单：操作人 Input（默认 `profile.displayName`）+ 原因 TextArea（必填）→ 确认后 `reverseBatch`；成功 `message.success`（含 `restoredProducts`/`reversedMovements` 与「同一文件可重新上传」提示）→ 关闭抽屉、刷新列表；409/400 用 `getProblemDetailMessage` 显示在 Modal 内 Alert，不关 Modal；
- 撤销后抽屉内批次数据就地刷新（重新 `getBatch`）。

### 5.3 InventoryImportPage / SalesImportPage（共享结构）

- 表单：`Upload.Dragger`（accept .xls/.xlsx，`beforeUpload` 校验扩展名与单文件，**返回 false** 由表单接管，不做自动上传）+ 各自参数：
  - 初始库存：warehouseId `Select`（`listWarehouses`，允许空 = 仓库待分配）+ 提示文案「未知条码将导致整批失败」；
  - 每日销售：businessDate `DatePicker`（必填，默认今天）+ 提示「未识别供应商将归并、未知条码自动建待完善商品」；
- 提交：按钮 loading；`importInventory`/`importDailySales` 成功后渲染 `ImportResultPanel`；
- **ImportResultPanel**（两个页面共用的结果组件）：
  - `status=POSTED` → `Result`（antd）成功面板：行数 Descriptions（每日销售额外显示 salesRows/pendingProductsCreated/deductedProducts）+「查看批次」按钮（跳批次列表或直接开该批次详情抽屉）；
  - `status=FAILED` → Alert error（整批未入账）+ 行级错误 `Table`（rowNumber/barcode/message，≤50 条，注明「仅显示前 50 条」当 `errorRows > 50`）；
  - 请求本身 409 → Alert error 展示 `detail` 文案，表单保留已选文件可修改后重试；
- 成功后表单重置（文件清空），避免重复提交同一文件触发 409。

### 5.4 空状态

- 管理员未选门店：`Empty` +「请先选择门店」（顶栏选择器）；
- 批次列表空数据：Table 自带 empty（默认文案即可）。

## 6. 测试策略

- **api 层单测**（mock `http` 模块）：`listBatches` 查询参数序列化（undefined 不传）；`importInventory`/`importDailySales` 的 FormData 内容（file + warehouseId/businessDate）；
- **stores/app 单测**：loadStores 成功/失败置空；selectStore/clearStore；
- **路由工具单测**：`swapStoreIdInPath`（替换/无 storeId 段行为）；
- **页面 RTL 测试**：
  - ImportBatchesPage：mock api 渲染行；筛选提交带正确参数；分页 current 换算；未选门店空状态；
  - BatchDetailDrawer：撤销流（默认操作人/必填校验/成功回调/409 展示 detail）；`reversible=false` 不渲染按钮；
  - 上传页：选择文件后提交构建正确 FormData（mock api 断言）；POSTED 渲染成功面板；FAILED 渲染错误表；409 展示 detail；
  - MainLayout：菜单按 canManage 过滤（普通用户无导入组）；门店选择器切换触发 `swapStoreIdInPath` 导航；
- **手动闭环**（真实后端 + MySQL）：用真实 POS 文件（环境变量 `HAOWUGOU_POS_SALES_FILE`，桌面）走 每日销售上传 → 批次列表 → 详情 → 撤销 → 重传；初始库存用一个自建小 xlsx；验证 409 文案与 FAILED 错误表。

## 7. 明确不做

- 商品查询页（下一子切片；届时先补 `GET /api/categories` + `GET /api/suppliers` 后端接口）
- 导入文件模板下载（后端无接口）
- 撤销历史/审计列表页（后端无接口）
- 拖拽多文件、断点续传、异步导入进度轮询（后端为同步导入，无此形态）
