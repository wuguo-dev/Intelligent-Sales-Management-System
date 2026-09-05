# 前端认证骨架实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在仓库根目录新建 `haowugou-web/` 前端项目（React 18 + TS + Vite + antd 5 + React Router + Zustand + axios），实现登录、会话保持、身份展示与登出，跑通 CSRF 认证全流程。

**Architecture:** 纯前端新增，不触碰后端。Vite dev server 代理 `/api` → `localhost:8080`（同源，Cookie 天然生效）。认证数据流：取 `XSRF-TOKEN` Cookie → axios 拦截器回填 `X-XSRF-TOKEN` 头 → `POST /login` 成功存 zustand → 路由守卫靠 `GET /me` 恢复会话 → `POST /logout` 清理。身份状态只存内存，不写 localStorage。

**Tech Stack:** React 18/19（模板自带）· TypeScript strict · Vite · antd 5（含 `@ant-design/v5-patch-for-react-19`）· react-router-dom · zustand · axios · vitest + jsdom + @testing-library/react

**Spec:** `docs/design/2026-09-01-frontend-auth-skeleton-design.md`（本计划逐任务对应其 §3 目录结构、§4 数据流、§5 类型、§6 页面、§7 测试、§8 实现顺序）

## Global Constraints

- 只新增 `haowugou-web/` 下文件；**不修改、不 `git add` 后端任何文件**（工作区有后端未提交改动，本计划只提交前端文件）
- Node 24 / npm 11；包管理只用 npm
- 每个 commit 结尾加 `Co-Authored-By: Claude <noreply@anthropic.com>`；最后一步推送（分支 PR #4 已存在，推送自动更新）
- UI 文案中文（产品名「好物购」）；antd 全局 `zh_CN` locale
- 不引入 react-query 等重型数据层；身份状态只存内存（zustand），不写 localStorage
- 权限展示用后端下发的 `canManage`/`canViewCostAndProfit` 布尔，不得按 `roleId` 自行推断
- 提交前确认暂存区无真实凭据

---

### Task 1: 项目脚手架与工程配置

**Files:**
- Create: `haowugou-web/`（`npm create vite` 生成的全部文件）
- Modify: `haowugou-web/vite.config.ts`、`haowugou-web/package.json`、`haowugou-web/index.html`、`haowugou-web/src/main.tsx`
- Delete: `haowugou-web/src/App.tsx`、`haowugou-web/src/App.css`、`haowugou-web/src/index.css`、`haowugou-web/src/assets/`
- Create: `haowugou-web/src/test/setup.ts`

**Interfaces:**
- Consumes: 无
- Produces: 可运行的 Vite 项目骨架 + vitest 测试环境（后续所有任务的测试都跑在 `npm run test` 上）

- [ ] **Step 1: 生成脚手架并装依赖**

```bash
cd D:/Dev/Code/Project/Intelligent-Sales-Management-System-main
npm create vite@latest haowugou-web -- --template react-ts
cd haowugou-web
npm install
npm i antd @ant-design/icons @ant-design/v5-patch-for-react-19 react-router-dom zustand axios
npm i -D vitest jsdom @testing-library/react @testing-library/dom @testing-library/jest-dom @testing-library/user-event
```

Expected: 依赖安装成功，`package.json` 出现上述依赖。

- [ ] **Step 2: 配置 Vite 代理与 vitest**

改写 `haowugou-web/vite.config.ts`（模板原文件整体替换）：

```ts
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
});
```

- [ ] **Step 3: 写测试环境 setup 与测试脚本**

新建 `haowugou-web/src/test/setup.ts`：

```ts
import '@testing-library/jest-dom/vitest';

// antd 5 组件在 jsdom 下依赖 matchMedia（jsdom 未实现）
if (typeof window.matchMedia !== 'function') {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  });
}
```

`haowugou-web/package.json` 的 `scripts` 增加：

```json
"test": "vitest run"
```

- [ ] **Step 4: 清掉模板文件并占位 main**

删除 `src/App.tsx`、`src/App.css`、`src/index.css`、`src/assets/`。改写 `src/main.tsx`：

```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <div>好物购</div>
  </React.StrictMode>,
);
```

`index.html`：`<html lang="zh-CN">`，`<title>好物购</title>`。

- [ ] **Step 5: 验证构建与测试环境**

```bash
cd haowugou-web
npm run build
npm run test
```

Expected: `npm run build` 成功（无 TS 报错）；`npm run test` 成功（无测试，报告 0 个）。

- [ ] **Step 6: 提交**

```bash
git add haowugou-web
git commit -m "chore: 前端脚手架 haowugou-web（Vite + React + TS + antd + vitest）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: TS 类型与 axios 实例（CSRF 拦截器 + 401 处理）

**Files:**
- Create: `haowugou-web/src/api/types.ts`
- Create: `haowugou-web/src/api/http.ts`
- Test: `haowugou-web/src/api/http.test.ts`

**Interfaces:**
- Consumes: 无（Task 1 的 vitest 环境）
- Produces:
  - `types.ts` 导出 `StoreView`、`UserProfile`、`ProblemDetail`、`CsrfTokenResponse`（Task 3/4/5/6/7 全部引用）
  - `http.ts` 导出 `readCookie(name: string): string | null`、`attachCsrfHeader(config: InternalAxiosRequestConfig): InternalAxiosRequestConfig`、`getProblemDetailMessage(error: unknown): string | null`、`isLoginPath(pathname: string): boolean`、`CSRF_COOKIE_NAME`（值为 `'XSRF-TOKEN'`）、`CSRF_HEADER_NAME`（值为 `'X-XSRF-TOKEN'`），默认导出 axios 实例 `http`（`withCredentials: true` + 请求拦截器 + 响应 401 拦截）

- [ ] **Step 1: 写失败测试**

新建 `haowugou-web/src/api/http.test.ts`：

```ts
import { AxiosHeaders, type InternalAxiosRequestConfig } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import {
  attachCsrfHeader,
  CSRF_COOKIE_NAME,
  CSRF_HEADER_NAME,
  getProblemDetailMessage,
  isLoginPath,
  readCookie,
} from './http';

function makeConfig(): InternalAxiosRequestConfig {
  return { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
}

function clearCookies(): void {
  document.cookie.split(';').forEach((c) => {
    const name = c.trim().split('=')[0];
    document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
  });
}

describe('readCookie', () => {
  beforeEach(clearCookies);

  it('Cookie 存在时返回解码后的值', () => {
    document.cookie = 'a=1';
    document.cookie = 'b=hello%20world';
    expect(readCookie('b')).toBe('hello world');
  });

  it('Cookie 不存在时返回 null', () => {
    document.cookie = 'a=1';
    expect(readCookie('nope')).toBeNull();
  });
});

describe('attachCsrfHeader', () => {
  beforeEach(clearCookies);

  it('有 XSRF-TOKEN Cookie 时回填 X-XSRF-TOKEN 请求头', () => {
    document.cookie = `${CSRF_COOKIE_NAME}=abc123`;
    const config = attachCsrfHeader(makeConfig());
    expect(config.headers.get(CSRF_HEADER_NAME)).toBe('abc123');
  });

  it('无 Cookie 时不设置请求头', () => {
    document.cookie = 'a=1';
    const config = attachCsrfHeader(makeConfig());
    expect(config.headers.get(CSRF_HEADER_NAME)).toBeNull();
  });
});

describe('getProblemDetailMessage', () => {
  it('从 Problem Detail 响应提取 detail', () => {
    const error = {
      isAxiosError: true,
      response: { data: { title: '登录失败', status: 401, detail: '账号或密码错误' } },
    };
    expect(getProblemDetailMessage(error)).toBe('账号或密码错误');
  });

  it('detail 缺失时退回 title', () => {
    const error = {
      isAxiosError: true,
      response: { data: { title: '登录失败', status: 401 } },
    };
    expect(getProblemDetailMessage(error)).toBe('登录失败');
  });

  it('非 axios 错误返回 null', () => {
    expect(getProblemDetailMessage(new Error('boom'))).toBeNull();
  });
});

describe('isLoginPath', () => {
  it('登录页路径返回 true，其余返回 false', () => {
    expect(isLoginPath('/login')).toBe(true);
    expect(isLoginPath('/login/')).toBe(true);
    expect(isLoginPath('/')).toBe(false);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd haowugou-web
npm run test
```

Expected: FAIL，找不到 `./http` 模块。

- [ ] **Step 3: 写类型与实现**

新建 `haowugou-web/src/api/types.ts`：

```ts
/** 与后端 `AuthenticatedUserResponse.StoreResponse` 1:1。 */
export interface StoreView {
  id: number;
  storeCode: string;
  storeName: string;
}

export type UserRole = 'ADMIN' | 'USER';

/** 与后端 `AuthenticatedUserResponse` 1:1（login 与 me 共用）。 */
export interface UserProfile {
  userId: number;
  username: string;
  displayName: string;
  roleId: number; // 1 管理员，2 普通用户——仅展示用，权限判定用 canManage/canViewCostAndProfit
  role: UserRole;
  store: StoreView | null; // null = 管理员
  canManage: boolean; // 是否可执行导入、撤销等写操作
  canViewCostAndProfit: boolean; // 是否可看到含税成本价与毛利字段
}

/** 后端统一错误体（RFC 7807 Problem Detail）。 */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
}

export interface CsrfTokenResponse {
  token: string;
  headerName: string;
  parameterName: string;
}
```

新建 `haowugou-web/src/api/http.ts`：

```ts
import axios, { type InternalAxiosRequestConfig } from 'axios';
import type { ProblemDetail } from './types';

/** 与后端 CookieCsrfTokenRepository 的 Cookie 名一致。 */
export const CSRF_COOKIE_NAME = 'XSRF-TOKEN';
/** CSRF 令牌应放入的请求头（后端 csrfTokenResponse.headerName）。 */
export const CSRF_HEADER_NAME = 'X-XSRF-TOKEN';

export function readCookie(name: string): string | null {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = document.cookie.match(new RegExp(`(?:^|; )${escaped}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : null;
}

/** 请求拦截器：从 Cookie 读 CSRF 令牌回填请求头；无令牌时不动。 */
export function attachCsrfHeader(
  config: InternalAxiosRequestConfig,
): InternalAxiosRequestConfig {
  const token = readCookie(CSRF_COOKIE_NAME);
  if (token) {
    config.headers.set(CSRF_HEADER_NAME, token);
  }
  return config;
}

export function isProblemDetail(body: unknown): body is ProblemDetail {
  return (
    typeof body === 'object' &&
    body !== null &&
    'status' in body &&
    typeof (body as ProblemDetail).status === 'number'
  );
}

/** 从 axios 错误里提取 Problem Detail 的展示文案；非此类错误返回 null。 */
export function getProblemDetailMessage(error: unknown): string | null {
  if (!axios.isAxiosError(error) || !error.response) {
    return null;
  }
  const data = error.response.data;
  if (isProblemDetail(data)) {
    return data.detail ?? data.title ?? null;
  }
  return null;
}

/** 当前路径是否登录页——登录页上的 401 是登录失败本身，不做全局跳转。 */
export function isLoginPath(pathname: string): boolean {
  return pathname === '/login' || pathname.startsWith('/login/');
}

const http = axios.create({ withCredentials: true });

http.interceptors.request.use(attachCsrfHeader);

// 会话中途失效（401）：非登录页统一踢回登录页；登录页的 401 由页面自行展示错误。
http.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (
      axios.isAxiosError(error) &&
      error.response?.status === 401 &&
      !isLoginPath(window.location.pathname)
    ) {
      window.location.assign('/login');
    }
    return Promise.reject(error);
  },
);

export default http;
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd haowugou-web
npm run test
```

Expected: PASS（4 组用例）。

- [ ] **Step 5: 提交**

```bash
git add haowugou-web/src/api
git commit -m "feat: axios 实例与 CSRF 拦截器（含单测）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: 认证 API 封装

**Files:**
- Create: `haowugou-web/src/api/auth.ts`
- Test: `haowugou-web/src/api/auth.test.ts`

**Interfaces:**
- Consumes: Task 2 的 `http`（默认导出）、`readCookie`、`CSRF_COOKIE_NAME`，`types.ts` 的 `UserProfile`、`CsrfTokenResponse`
- Produces: `ensureCsrfToken(): Promise<void>`、`login(username: string, password: string): Promise<UserProfile>`、`fetchMe(): Promise<UserProfile>`、`logout(): Promise<void>`（Task 4 store、Task 6 LoginPage 使用）

- [ ] **Step 1: 写失败测试**

新建 `haowugou-web/src/api/auth.test.ts`：

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as auth from './auth';

const http = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));

vi.mock('./http', () => ({
  default: { get: http.get, post: http.post },
  readCookie: vi.fn(),
  CSRF_COOKIE_NAME: 'XSRF-TOKEN',
}));

import { readCookie } from './http';

describe('ensureCsrfToken', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('Cookie 缺失时请求 GET /api/auth/csrf', async () => {
    vi.mocked(readCookie).mockReturnValue(null);
    http.get.mockResolvedValue({ data: { token: 't', headerName: 'X-XSRF-TOKEN', parameterName: '_csrf' } });
    await auth.ensureCsrfToken();
    expect(http.get).toHaveBeenCalledWith('/api/auth/csrf');
  });

  it('Cookie 已存在时不发请求', async () => {
    vi.mocked(readCookie).mockReturnValue('has-token');
    await auth.ensureCsrfToken();
    expect(http.get).not.toHaveBeenCalled();
  });
});

describe('login / fetchMe / logout', () => {
  it('login 提交用户名密码并返回资料', async () => {
    const profile = { userId: 1, username: 'admin', displayName: '管理员', roleId: 1, role: 'ADMIN', store: null, canManage: true, canViewCostAndProfit: true };
    http.post.mockResolvedValue({ data: profile });
    await expect(auth.login('admin', 'secret')).resolves.toEqual(profile);
    expect(http.post).toHaveBeenCalledWith('/api/auth/login', { username: 'admin', password: 'secret' });
  });

  it('fetchMe 返回当前登录者资料', async () => {
    http.get.mockResolvedValue({ data: { userId: 2 } });
    await auth.fetchMe();
    expect(http.get).toHaveBeenCalledWith('/api/auth/me');
  });

  it('logout 提交登出', async () => {
    http.post.mockResolvedValue({ status: 204 });
    await auth.logout();
    expect(http.post).toHaveBeenCalledWith('/api/auth/logout');
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd haowugou-web
npm run test
```

Expected: FAIL，找不到 `./auth` 模块。

- [ ] **Step 3: 实现**

新建 `haowugou-web/src/api/auth.ts`：

```ts
import http, { CSRF_COOKIE_NAME, readCookie } from './http';
import type { CsrfTokenResponse, UserProfile } from './types';

/**
 * 确保存在 CSRF 令牌：Cookie 里没有 XSRF-TOKEN 时先 GET /api/auth/csrf
 * （该请求顺带种下 Cookie）。登录/登出前的状态变更请求都要令牌。
 */
export async function ensureCsrfToken(): Promise<void> {
  if (readCookie(CSRF_COOKIE_NAME)) {
    return;
  }
  await http.get<CsrfTokenResponse>('/api/auth/csrf');
}

/** 账号密码登录；失败（401）时抛出 axios 错误，文案从 Problem Detail 提取。 */
export async function login(username: string, password: string): Promise<UserProfile> {
  const { data } = await http.post<UserProfile>('/api/auth/login', { username, password });
  return data;
}

/** 查当前登录者资料；未登录 401 抛错，由路由守卫决定跳转。 */
export async function fetchMe(): Promise<UserProfile> {
  const { data } = await http.get<UserProfile>('/api/auth/me');
  return data;
}

/** 登出；204 视为成功。 */
export async function logout(): Promise<void> {
  await http.post('/api/auth/logout');
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd haowugou-web
npm run test
```

Expected: PASS（5 条用例）。

- [ ] **Step 5: 提交**

```bash
git add haowugou-web/src/api
git commit -m "feat: 认证 API 封装与 CSRF 令牌确保（含单测）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: 认证状态 store（zustand）

**Files:**
- Create: `haowugou-web/src/stores/auth.ts`
- Test: `haowugou-web/src/stores/auth.test.ts`

**Interfaces:**
- Consumes: Task 3 的 `login`/`fetchMe`/`logout`，`types.ts` 的 `UserProfile`
- Produces: `useAuthStore`（zustand），状态 `profile: UserProfile | null`、`loading: boolean`，action `login(username, password)`、`fetchMe()`、`logout()`、`clear()`（Task 5 守卫、Task 6/7 页面使用）

- [ ] **Step 1: 写失败测试**

新建 `haowugou-web/src/stores/auth.test.ts`：

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { UserProfile } from '../api/types';
import { useAuthStore } from './auth';

vi.mock('../api/auth', () => ({
  login: vi.fn(),
  fetchMe: vi.fn(),
  logout: vi.fn(),
}));

import * as authApi from '../api/auth';

const admin: UserProfile = {
  userId: 1,
  username: 'admin',
  displayName: '管理员',
  roleId: 1,
  role: 'ADMIN',
  store: null,
  canManage: true,
  canViewCostAndProfit: true,
};

describe('auth store', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ profile: null, loading: false });
  });

  it('login 成功后保存资料', async () => {
    vi.mocked(authApi.login).mockResolvedValue(admin);
    await useAuthStore.getState().login('admin', 'secret');
    expect(useAuthStore.getState().profile).toEqual(admin);
  });

  it('login 失败时抛错且不保存资料', async () => {
    vi.mocked(authApi.login).mockRejectedValue(new Error('401'));
    await expect(useAuthStore.getState().login('x', 'y')).rejects.toThrow('401');
    expect(useAuthStore.getState().profile).toBeNull();
  });

  it('fetchMe 成功保存资料并结束 loading', async () => {
    vi.mocked(authApi.fetchMe).mockResolvedValue(admin);
    await useAuthStore.getState().fetchMe();
    expect(useAuthStore.getState().profile).toEqual(admin);
    expect(useAuthStore.getState().loading).toBe(false);
  });

  it('fetchMe 失败（401）保持未登录并结束 loading', async () => {
    vi.mocked(authApi.fetchMe).mockRejectedValue(new Error('401'));
    await expect(useAuthStore.getState().fetchMe()).rejects.toThrow('401');
    expect(useAuthStore.getState().profile).toBeNull();
    expect(useAuthStore.getState().loading).toBe(false);
  });

  it('logout 成功后清空资料', async () => {
    useAuthStore.setState({ profile: admin });
    vi.mocked(authApi.logout).mockResolvedValue(undefined);
    await useAuthStore.getState().logout();
    expect(useAuthStore.getState().profile).toBeNull();
  });

  it('clear 直接清空资料', () => {
    useAuthStore.setState({ profile: admin });
    useAuthStore.getState().clear();
    expect(useAuthStore.getState().profile).toBeNull();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd haowugou-web
npm run test
```

Expected: FAIL，找不到 `./auth`。

- [ ] **Step 3: 实现**

新建 `haowugou-web/src/stores/auth.ts`：

```ts
import { create } from 'zustand';
import * as authApi from '../api/auth';
import type { UserProfile } from '../api/types';

interface AuthState {
  /** 当前登录者；null = 未登录。身份只存内存，服务端会话 Cookie 才是权威。 */
  profile: UserProfile | null;
  /** fetchMe 恢复会话中。 */
  loading: boolean;
  login: (username: string, password: string) => Promise<void>;
  fetchMe: () => Promise<void>;
  logout: () => Promise<void>;
  clear: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  profile: null,
  loading: false,

  login: async (username, password) => {
    const profile = await authApi.login(username, password);
    set({ profile });
  },

  fetchMe: async () => {
    set({ loading: true });
    try {
      const profile = await authApi.fetchMe();
      set({ profile });
    } finally {
      set({ loading: false });
    }
  },

  logout: async () => {
    await authApi.logout();
    set({ profile: null });
  },

  clear: () => set({ profile: null }),
}));
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd haowugou-web
npm run test
```

Expected: PASS（6 条用例）。

- [ ] **Step 5: 提交**

```bash
git add haowugou-web/src/stores
git commit -m "feat: 认证状态 store（zustand，含单测）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: 路由与登录守卫

**Files:**
- Create: `haowugou-web/src/router/index.tsx`
- Test: `haowugou-web/src/router/index.test.tsx`

**Interfaces:**
- Consumes: Task 4 的 `useAuthStore`；Task 6/7 的页面与布局组件（本任务先建占位版本，Task 6/7 替换）
- Produces: `router`（createBrowserRouter）、`protectedLoader(): Promise<null | Response>`（Task 8 的 main.tsx 使用 `router`）

- [ ] **Step 1: 写失败测试**

新建 `haowugou-web/src/router/index.test.tsx`：

```tsx
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { UserProfile } from '../api/types';
import { useAuthStore } from '../stores/auth';
import { protectedLoader } from './index';

// mock 掉 API 层：store 的 fetchMe action 会调用这里的假 fetchMe
vi.mock('../api/auth', () => ({ fetchMe: vi.fn() }));

import * as authApi from '../api/auth';

const admin: UserProfile = {
  userId: 1,
  username: 'admin',
  displayName: '管理员',
  roleId: 1,
  role: 'ADMIN',
  store: null,
  canManage: true,
  canViewCostAndProfit: true,
};

describe('protectedLoader', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ profile: null });
  });

  it('已有资料时不查 me 直接放行', async () => {
    const fetchMe = vi.fn();
    useAuthStore.setState({ profile: admin, fetchMe });
    const result = await protectedLoader();
    expect(result).toBeNull();
    expect(fetchMe).not.toHaveBeenCalled();
  });

  it('无资料时查 me 恢复会话', async () => {
    vi.mocked(authApi.fetchMe).mockResolvedValue(admin);
    const result = await protectedLoader();
    expect(result).toBeNull();
    expect(authApi.fetchMe).toHaveBeenCalled();
    expect(useAuthStore.getState().profile).toEqual(admin);
  });

  it('me 返回 401 时重定向到 /login', async () => {
    vi.mocked(authApi.fetchMe).mockRejectedValue(new Error('401'));
    const result = await protectedLoader();
    expect(result).not.toBeNull();
    expect((result as Response).headers.get('Location')).toBe('/login');
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd haowugou-web
npm run test
```

Expected: FAIL，找不到 `./index`。

- [ ] **Step 3: 实现路由与守卫**

新建 `haowugou-web/src/router/index.tsx`：

```tsx
import { createBrowserRouter, redirect } from 'react-router-dom';
import MainLayout from '../layouts/MainLayout';
import LoginPage from '../pages/LoginPage';
import HomePage from '../pages/HomePage';
import { useAuthStore } from '../stores/auth';

/**
 * 受保护路由的 loader：无资料时先 GET /me 恢复会话；
 * 401（未登录/会话过期）重定向到 /login。
 */
export async function protectedLoader(): Promise<null | Response> {
  const store = useAuthStore.getState();
  if (store.profile) {
    return null;
  }
  try {
    await store.fetchMe();
    return null;
  } catch {
    return redirect('/login');
  }
}

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: <MainLayout />,
    loader: protectedLoader,
    children: [{ index: true, element: <HomePage /> }],
  },
]);
```

本任务还需创建 Task 6/7 的占位组件（否则编译失败）——直接在 Task 6/7 实现时替换：

- `src/layouts/MainLayout.tsx`：`export default function MainLayout() { return <div>MainLayout</div>; }`
- `src/pages/LoginPage.tsx`：`export default function LoginPage() { return <div>LoginPage</div>; }`
- `src/pages/HomePage.tsx`：`export default function HomePage() { return <div>HomePage</div>; }`

- [ ] **Step 4: 跑测试确认通过**

```bash
cd haowugou-web
npm run test
```

Expected: PASS（3 条用例，占位组件不影响 loader 测试）。

- [ ] **Step 5: 提交**

```bash
git add haowugou-web/src/router haowugou-web/src/layouts haowugou-web/src/pages
git commit -m "feat: 路由与登录守卫（含单测）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: 登录页

**Files:**
- Modify: `haowugou-web/src/pages/LoginPage.tsx`（替换占位）
- Test: `haowugou-web/src/pages/LoginPage.test.tsx`

**Interfaces:**
- Consumes: Task 3 `ensureCsrfToken`、Task 2 `getProblemDetailMessage`、Task 4 `useAuthStore.login`、`types.ts`
- Produces: 完整登录页（antd Form + 错误 Alert + 提交 loading + 成功跳转 `/`）

- [ ] **Step 1: 写失败测试**

新建 `haowugou-web/src/pages/LoginPage.test.tsx`：

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { UserProfile } from '../api/types';
import { useAuthStore } from '../stores/auth';
import LoginPage from './LoginPage';

// store 的 login action 会调用这里的假 login；ensureCsrfToken 挂载时被调用
vi.mock('../api/auth', () => ({
  ensureCsrfToken: vi.fn().mockResolvedValue(undefined),
  login: vi.fn(),
}));

// 部分 mock：http.ts 其余导出保持真实，只替换 getProblemDetailMessage
vi.mock('../api/http', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/http')>();
  return { ...actual, getProblemDetailMessage: vi.fn() };
});

import * as authApi from '../api/auth';
import { getProblemDetailMessage } from '../api/http';

const admin: UserProfile = {
  userId: 1,
  username: 'admin',
  displayName: '管理员',
  roleId: 1,
  role: 'ADMIN',
  store: null,
  canManage: true,
  canViewCostAndProfit: true,
};

function renderLogin() {
  const router = createMemoryRouter(
    [
      { path: '/login', element: <LoginPage /> },
      { path: '/', element: <div>首页占位</div> },
    ],
    { initialEntries: ['/login'] },
  );
  render(<RouterProvider router={router} />);
  return router;
}

describe('LoginPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ profile: null, loading: false });
  });

  it('提交用户名密码调用 login 并跳转首页', async () => {
    vi.mocked(authApi.login).mockResolvedValue(admin);
    renderLogin();
    await userEvent.type(screen.getByPlaceholderText('用户名'), 'admin');
    await userEvent.type(screen.getByPlaceholderText('密码'), 'secret');
    await userEvent.click(screen.getByRole('button', { name: /登\s*录/ }));

    expect(await screen.findByText('首页占位')).toBeInTheDocument();
    expect(useAuthStore.getState().profile?.username).toBe('admin');
  });

  it('登录失败展示后端错误文案', async () => {
    vi.mocked(authApi.login).mockRejectedValue(new Error('401'));
    vi.mocked(getProblemDetailMessage).mockReturnValue('账号或密码错误');
    renderLogin();
    await userEvent.type(screen.getByPlaceholderText('用户名'), 'admin');
    await userEvent.type(screen.getByPlaceholderText('密码'), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: /登\s*录/ }));

    expect(await screen.findByText('账号或密码错误')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd haowugou-web
npm run test
```

Expected: FAIL（占位组件不渲染表单）。

- [ ] **Step 3: 实现登录页**

改写 `haowugou-web/src/pages/LoginPage.tsx`：

```tsx
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Form, Input } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ensureCsrfToken } from '../api/auth';
import { getProblemDetailMessage } from '../api/http';
import { useAuthStore } from '../stores/auth';

interface LoginFormValues {
  username: string;
  password: string;
}

export default function LoginPage() {
  const navigate = useNavigate();
  const login = useAuthStore((s) => s.login);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 登录是状态变更请求，先确保 CSRF 令牌（Cookie 已有则跳过，不发请求）。
  useEffect(() => {
    void ensureCsrfToken();
  }, []);

  const onFinish = async (values: LoginFormValues) => {
    setSubmitting(true);
    setError(null);
    try {
      await login(values.username, values.password);
      navigate('/', { replace: true });
    } catch (e) {
      setError(getProblemDetailMessage(e) ?? '登录失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: '#f5f5f5',
      }}
    >
      <Card title="好物购" style={{ width: 360 }}>
        {error && (
          <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />
        )}
        <Form<LoginFormValues> onFinish={onFinish} size="large">
          <Form.Item
            name="username"
            rules={[
              { required: true, message: '请输入用户名' },
              { max: 64, message: '用户名最长 64 个字符' },
            ]}
          >
            <Input prefix={<UserOutlined />} placeholder="用户名" autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password
              prefix={<LockOutlined />}
              placeholder="密码"
              autoComplete="current-password"
            />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={submitting}>
            登 录
          </Button>
        </Form>
      </Card>
    </div>
  );
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd haowugou-web
npm run test
```

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add haowugou-web/src/pages
git commit -m "feat: 登录页（含单测）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 7: 主布局与首页身份展示

**Files:**
- Modify: `haowugou-web/src/layouts/MainLayout.tsx`、`haowugou-web/src/pages/HomePage.tsx`（替换占位）
- Test: `haowugou-web/src/layouts/MainLayout.test.tsx`、`haowugou-web/src/pages/HomePage.test.tsx`

**Interfaces:**
- Consumes: Task 4 `useAuthStore`（`profile`、`logout`）；Task 5 的 router 已把本组件挂到 `/`
- Produces: 顶栏（标题 + 用户下拉：身份/角色/门店/退出登录）与首页身份信息卡

- [ ] **Step 1: 写失败测试**

新建 `haowugou-web/src/pages/HomePage.test.tsx`：

```tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { UserProfile } from '../api/types';
import { useAuthStore } from '../stores/auth';
import HomePage from './HomePage';

const admin: UserProfile = {
  userId: 1,
  username: 'admin',
  displayName: '管理员',
  roleId: 1,
  role: 'ADMIN',
  store: null,
  canManage: true,
  canViewCostAndProfit: true,
};

describe('HomePage', () => {
  it('展示账号、角色与权限标记', () => {
    useAuthStore.setState({ profile: admin });
    render(<HomePage />);
    expect(screen.getByText('管理员')).toBeInTheDocument();
    expect(screen.getByText('admin')).toBeInTheDocument();
    expect(screen.getByText('ADMIN')).toBeInTheDocument();
    expect(screen.getByText('可管理（导入/撤销）')).toBeInTheDocument();
    expect(screen.getByText('可查看成本与毛利')).toBeInTheDocument();
  });

  it('普通用户展示绑定门店', () => {
    useAuthStore.setState({
      profile: {
        userId: 2,
        username: 'store1user',
        displayName: '门店一用户',
        roleId: 2,
        role: 'USER',
        store: { id: 1, storeCode: 'S001', storeName: '门店一' },
        canManage: false,
        canViewCostAndProfit: false,
      },
    });
    render(<HomePage />);
    expect(screen.getByText(/S001 · 门店一/)).toBeInTheDocument();
  });

  it('未登录（profile 为 null）时不渲染内容', () => {
    useAuthStore.setState({ profile: null });
    const { container } = render(<HomePage />);
    expect(container).toBeEmptyDOMElement();
  });
});
```

新建 `haowugou-web/src/layouts/MainLayout.test.tsx`：

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { UserProfile } from '../api/types';
import { useAuthStore } from '../stores/auth';
import MainLayout from './MainLayout';

vi.mock('../api/auth', () => ({ logout: vi.fn().mockResolvedValue(undefined) }));

import * as authApi from '../api/auth';

const user: UserProfile = {
  userId: 2,
  username: 'store1user',
  displayName: '门店一用户',
  roleId: 2,
  role: 'USER',
  store: { id: 1, storeCode: 'S001', storeName: '门店一' },
  canManage: false,
  canViewCostAndProfit: false,
};

describe('MainLayout', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ profile: user });
  });

  it('顶栏展示用户名与门店', () => {
    const router = createMemoryRouter(
      [
        { path: '/', element: <MainLayout />, children: [{ index: true, element: <div>内容区</div> }] },
        { path: '/login', element: <div>登录页</div> },
      ],
      { initialEntries: ['/'] },
    );
    render(<RouterProvider router={router} />);
    expect(screen.getByText('门店一用户')).toBeInTheDocument();
    expect(screen.getByText('好物购')).toBeInTheDocument();
    expect(screen.getByText('内容区')).toBeInTheDocument();
  });

  it('点击退出登录调用 logout 并回登录页', async () => {
    const router = createMemoryRouter(
      [
        { path: '/', element: <MainLayout />, children: [{ index: true, element: <div>内容区</div> }] },
        { path: '/login', element: <div>登录页</div> },
      ],
      { initialEntries: ['/'] },
    );
    render(<RouterProvider router={router} />);

    await userEvent.click(screen.getByText('门店一用户'));
    await userEvent.click(await screen.findByText('退出登录'));

    expect(authApi.logout).toHaveBeenCalled();
    expect(useAuthStore.getState().profile).toBeNull();
    expect(await screen.findByText('登录页')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd haowugou-web
npm run test
```

Expected: FAIL（占位组件无这些内容）。

- [ ] **Step 3: 实现主布局**

改写 `haowugou-web/src/layouts/MainLayout.tsx`：

```tsx
import { LogoutOutlined, UserOutlined } from '@ant-design/icons';
import { Avatar, Dropdown, Layout, Space, Tag, Typography } from 'antd';
import { Outlet, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../stores/auth';

export default function MainLayout() {
  const navigate = useNavigate();
  const profile = useAuthStore((s) => s.profile);
  const logout = useAuthStore((s) => s.logout);

  const onLogout = async () => {
    try {
      await logout();
    } finally {
      navigate('/login', { replace: true });
    }
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Header
        style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}
      >
        <Typography.Title level={4} style={{ color: '#fff', margin: 0 }}>
          好物购
        </Typography.Title>
        <Dropdown
          menu={{
            items: [
              {
                key: 'identity',
                disabled: true,
                label: (
                  <Space>
                    <Tag color={profile?.canManage ? 'gold' : 'blue'}>{profile?.role}</Tag>
                    {profile?.store ? profile.store.storeName : '未绑定门店（管理员）'}
                  </Space>
                ),
              },
              { type: 'divider' },
              { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: onLogout },
            ],
          }}
        >
          <Space style={{ cursor: 'pointer', color: '#fff' }}>
            <Avatar size="small" icon={<UserOutlined />} />
            {profile?.displayName ?? profile?.username ?? '未登录'}
          </Space>
        </Dropdown>
      </Layout.Header>
      <Layout.Content style={{ padding: 24 }}>
        <Outlet />
      </Layout.Content>
    </Layout>
  );
}
```

- [ ] **Step 4: 实现首页**

改写 `haowugou-web/src/pages/HomePage.tsx`：

```tsx
import { Card, Descriptions, Tag } from 'antd';
import { useAuthStore } from '../stores/auth';

export default function HomePage() {
  const profile = useAuthStore((s) => s.profile);
  if (!profile) {
    return null;
  }
  return (
    <Card title="当前用户">
      <Descriptions column={1} bordered>
        <Descriptions.Item label="账号ID">{profile.userId}</Descriptions.Item>
        <Descriptions.Item label="登录名">{profile.username}</Descriptions.Item>
        <Descriptions.Item label="展示名">{profile.displayName}</Descriptions.Item>
        <Descriptions.Item label="角色">
          <Tag color={profile.canManage ? 'gold' : 'blue'}>{profile.role}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label="绑定门店">
          {profile.store ? `${profile.store.storeCode} · ${profile.store.storeName}` : '未绑定（管理员）'}
        </Descriptions.Item>
        <Descriptions.Item label="权限">
          {profile.canManage && <Tag color="gold">可管理（导入/撤销）</Tag>}
          {profile.canViewCostAndProfit && <Tag color="purple">可查看成本与毛利</Tag>}
        </Descriptions.Item>
      </Descriptions>
    </Card>
  );
}
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd haowugou-web
npm run test
```

Expected: PASS（HomePage 3 条 + MainLayout 2 条）。

- [ ] **Step 6: 提交**

```bash
git add haowugou-web/src/layouts haowugou-web/src/pages
git commit -m "feat: 主布局与首页身份展示（含单测）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 8: 全局接入 antd 中文环境并完成验证

**Files:**
- Modify: `haowugou-web/src/main.tsx`
- Test: 无新增（跑全量）

**Interfaces:**
- Consumes: Task 5 `router`、Task 6/7 全部组件
- Produces: 可运行的完整应用；全量测试与构建通过

- [ ] **Step 1: 改写 main.tsx 接入 antd 与路由**

改写 `haowugou-web/src/main.tsx`：

```tsx
import '@ant-design/v5-patch-for-react-19';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import React from 'react';
import ReactDOM from 'react-dom/client';
import { RouterProvider } from 'react-router-dom';
import { router } from './router';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN}>
      <RouterProvider router={router} />
    </ConfigProvider>
  </React.StrictMode>,
);
```

- [ ] **Step 2: 全量测试与构建**

```bash
cd haowugou-web
npm run test
npm run build
```

Expected: 全部单测 PASS；`npm run build` 无 TS 报错。

- [ ] **Step 3: 手动闭环验证（需要后端在跑）**

```bash
cd haowugou-web
npm run dev
```

浏览器打开 `http://localhost:5173/login`，依次验证（后端需已启动，见 CLAUDE.md 启动方式，需 MySQL 与 `application-local.yml` 凭据）：

1. 打开登录页 → 开发者工具 Network 看到 `GET /api/auth/csrf` 且响应 Set-Cookie 里出现 `XSRF-TOKEN`（JS 可读，非 HttpOnly）；
2. 输入错误密码登录 → 表单上方出现红色 Alert「账号或密码错误」，请求头带 `X-XSRF-TOKEN`；
3. 输入正确账号登录 → 跳转 `/`，顶栏显示展示名，首页显示身份信息卡；
4. 刷新页面 → 仍停留在 `/`（守卫走 `GET /api/auth/me` 恢复会话）；
5. 点「退出登录」→ 回到 `/login`；此时访问 `/` 会被重定向回 `/login`。

Expected: 5 步全部符合预期。若第 3 步登录报 403，检查 `XSRF-TOKEN` Cookie 是否在登录请求前已种下（浏览器首次访问时 `GET /api/auth/csrf` 失败或被代理吞掉时会出现此问题）。

- [ ] **Step 4: 更新设计文档状态与提交**

`docs/design/2026-09-01-frontend-auth-skeleton-design.md` 首行「状态：已设计（待实现）」改为「状态：已实现」。

```bash
git add haowugou-web docs/design/2026-09-01-frontend-auth-skeleton-design.md
git commit -m "chore: 前端接入 antd 中文环境，认证骨架完成

Co-Authored-By: Claude <noreply@anthropic.com>"
```

- [ ] **Step 5: 推送**

```bash
git push
```

Expected: 分支推送到 origin（PR #4 自动带上全部前端提交）。推送前确认暂存区不含真实凭据。