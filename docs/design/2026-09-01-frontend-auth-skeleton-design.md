# 前端认证骨架设计

- 状态：已设计（待实现）
- 功能切片：`codex/import-batch-reversal`（前端骨架从当前分支起，技术栈 React，与后端无关）
- 对应架构规范：好物购项目整体架构规范 v1.0（§10 HTTP 接口规范、§19 Git 与交付规范；规范本身是后端视角，前端为新地面，本切片只遵守其后端契约与交付章节）
- 技术栈：**React 18 + TypeScript + Vite + Ant Design 5 + React Router + Zustand + axios**（用户指定 React 栈，替换最初议定的 Vue3 + Element Plus；认证契约与框架无关，后端零改动）
- 范围：认证骨架（登录页 + 登录后布局 + 登出），跑通 CSRF → 登录 → 会话保持 → 登出全流程，业务页后续逐个加

## 1. 业务目标

三件事：

1. **登录**：`POST /api/auth/login` 账号密码登录，走 CSRF 防护全流程；
2. **会话保持**：`GET /api/auth/me` 恢复登录态（页面刷新、路由切换、会话过期兜底）；
3. **身份展示与登出**：登录后顶栏展示当前用户身份/角色/绑定门店，`POST /api/auth/logout` 登出回登录页。

## 2. 后端接口契约

| 接口 | 方法 | 认证 | 说明 |
|------|------|------|------|
| `/api/auth/csrf` | GET | 免登录 | 下发 `XSRF-TOKEN` Cookie（`httpOnly=false`，JS 可读）+ 返回 `{token, headerName, parameterName}` |
| `/api/auth/login` | POST | 免登录，需 CSRF 头 | JSON `{username, password}` → 200 `AuthenticatedUserResponse`；401 统一文案 |
| `/api/auth/me` | GET | 登录态 | → 200 `AuthenticatedUserResponse`；401 未登录；404 账号已停用/删除 |
| `/api/auth/logout` | POST | 登录态，需 CSRF 头 | → 204；服务端 `invalidateHttpSession` + 删 `JSESSIONID` Cookie |

### 2.1 CSRF 细节（易错点）

- `GET /api/auth/csrf` 顺带种下 `XSRF-TOKEN` Cookie（`CookieCsrfTokenRepository.withHttpOnlyFalse()`），前端 JS 从 Cookie 读令牌，回填到 `headerName`（默认 `X-XSRF-TOKEN`）请求头；**所有状态变更请求（登录、登出）必须带该头**，否则 403。
- 登录成功后服务端换会话 ID（防会话固定），但 CSRF 令牌在 Cookie 里不随会话失效，**登录后不需要重新取令牌**。
- `username` 区分大小写（数据库 `utf8mb4_bin`）、最长 64 字符，前端表单校验对齐。

### 2.2 登录成功响应（`AuthenticatedUserResponse`，login 与 me 共用）

```json
{
  "userId": 1,
  "username": "admin",
  "displayName": "管理员",
  "roleId": 1,
  "role": "ADMIN",
  "store": { "id": 1, "storeCode": "S001", "storeName": "XX门店" },
  "canManage": true,
  "canViewCostAndProfit": true
}
```

`store` 为 null 表示管理员（不绑门店、可跨门店）；普通用户一定带门店。`canManage`/`canViewCostAndProfit` 是后端算好的权限结论，**前端不得自行按 `roleId` 推断**（两侧口径会走偏）。

### 2.3 统一错误体（Problem Detail，RFC 7807）

```json
{ "type": "about:blank", "title": "登录失败", "status": 401, "detail": "账号或密码错误" }
```

- 登录失败 401 固定回「账号或密码错误」，不区分账号不存在与密码错误（防账号枚举），前端照文案展示即可；
- `/api/auth/me` 返回 404 时 title 为「账号不存在或已停用」，前端引导重新登录。

## 3. 技术栈与目录结构

项目位于仓库根目录 `haowugou-web/`，与后端同仓库同分支演进。Vite dev server 代理 `/api` → `http://localhost:8080`（同源，Cookie 天然生效，后端零改动）。

```
haowugou-web/
├── index.html
├── package.json / vite.config.ts / tsconfig.json   # vite.config.ts 含 proxy: /api → http://localhost:8080
└── src/
    ├── main.tsx                # 挂载 Router + antd ConfigProvider（zh_CN locale）
    ├── App.tsx                 # 路由出口
    ├── api/
    │   ├── http.ts             # axios 实例：withCredentials + CSRF 拦截器 + 401 处理
    │   └── auth.ts             # login/me/logout/csrf 调用 + TS 类型（契约 1:1）
    ├── stores/auth.ts          # zustand：profile 状态与 login/fetchMe/logout action
    ├── router/index.tsx        # createBrowserRouter + 路由守卫
    ├── layouts/MainLayout.tsx  # antd Layout：顶栏身份/角色/门店 + 登出下拉
    └── pages/
        ├── LoginPage.tsx       # 登录表单
        └── HomePage.tsx        # 登录后首页：身份信息展示（业务页挂载点）
```

依赖：`antd` `react-router-dom` `zustand` `axios`；开发依赖：`vite` `typescript` `vitest` `@testing-library/react`。

## 4. 认证数据流

1. **取令牌**：登录页挂载时确保存在 `XSRF-TOKEN` Cookie（没有则 `GET /api/auth/csrf`）；
2. **请求拦截**：axios 请求拦截器从 Cookie 读 `XSRF-TOKEN` 回填 `X-XSRF-TOKEN` 头；实例 `withCredentials: true`（`JSESSIONID` 自动携带）；
3. **登录**：`POST /api/auth/login` `{username, password}` → 200 把响应存 zustand → 跳首页；401 表单展示「账号或密码错误」；
4. **会话恢复**：路由守卫进入受保护页前先 `GET /api/auth/me`（store 未加载时），401 踢回 `/login`——覆盖刷新与会话过期；
5. **登出**：`POST /api/auth/logout` → 204 → 清 store → 回 `/login`；
6. **全局兜底**：axios 响应拦截器在非登录页收到 401（会话中途失效）统一踢回 `/login`。

身份状态只存内存（zustand），**不写 localStorage**——服务端会话 Cookie 才是权威，前端只管展示。

## 5. TS 类型（与后端 1:1）

```ts
interface StoreView { id: number; storeCode: string; storeName: string }

interface UserProfile {
  userId: number;
  username: string;
  displayName: string;
  roleId: number;                 // 1 管理员，2 普通用户（仅展示用，权限判定用下面两个布尔）
  role: 'ADMIN' | 'USER';
  store: StoreView | null;        // null = 管理员
  canManage: boolean;             // 是否可执行导入、撤销等写操作
  canViewCostAndProfit: boolean;  // 是否可看到含税成本价与毛利字段
}

interface ProblemDetail {         // 统一错误体
  type?: string;
  title?: string;
  status: number;
  detail?: string;
}

interface CsrfTokenResponse {
  token: string;
  headerName: string;             // 默认 X-XSRF-TOKEN
  parameterName: string;          // 默认 _csrf
}
```

## 6. 页面与组件设计

- **LoginPage**：antd `Form`（用户名/密码，用户名最大 64）+ 登录按钮（loading 态）+ 错误 `Alert`（文案走后端 detail）；成功 `navigate('/')`。
- **MainLayout**：antd `Layout`——Header 左侧「好物购」标题；右侧 `Dropdown`（头像 + displayName，展开显示角色 `Tag` 与门店名）含「退出登录」项；Content 内 `Outlet` 渲染子路由。
- **HomePage**：`Card` + `Descriptions` 展示 userId / username / displayName / 角色 / 绑定门店（管理员显示「未绑定（管理员）」），`canManage`、`canViewCostAndProfit` 用两个 `Tag` 标注；作为后续业务页的挂载点。

## 7. 测试策略

- **单元测试**（Vitest + @testing-library/react）：CSRF 拦截器抽成纯函数（读 Cookie → 回填请求头），测无令牌/有令牌两分支；auth store 测 login/fetchMe/logout 状态流转（mock http 层）；
- **手动闭环**：`npm run dev`（:5173）+ 本地后端（需 MySQL 与 `application-local.yml` 凭据），走 登录 → 刷新页面（me 恢复会话）→ 登出 → 已失效跳转。

## 8. 实现顺序

1. 脚手架：`npm create vite`（react-ts 模板）+ 依赖安装；
2. `api/http.ts`（axios 实例 + CSRF 拦截器 + 401 处理）→ `api/auth.ts`（类型 + 调用）；
3. `stores/auth.ts`（zustand）；
4. `router/index.tsx`（路由 + 守卫）；
5. `LoginPage` → `MainLayout` → `HomePage`；
6. 测试与手动闭环验证。

## 9. 明确不做

- 不做业务页面（商品/库存/仓库/导入批次/每日销售导入/经营指标）——后续切片逐个加；
- 不做生产部署集成（build 产物进 bootstrap 静态目录、nginx 反代等方案）——等业务页完成后再定，届时才需要评估部署域下 Cookie 的跨域/安全属性；
- 不引入 react-query 等重型数据层——骨架阶段 axios + zustand 足够。