# 前端导入切片实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 haowugou-web 实现导入链路前端闭环：侧边菜单 + 门店选择器外壳、批次列表（筛选/分页）、详情抽屉（问题行/撤销）、初始库存与每日销售两个上传页。

**Architecture:** 纯前端新增与重构，不碰后端。业务路由统一挂 `/stores/:storeId/`（与后端路径形态一致），URL 是门店的唯一权威来源——选择器切换只改写 URL 段、页面从 `useParams` 读 storeId、进入页面时同步回 app store 供菜单与选择器显示。上传走 axios FormData（CSRF 拦截器已全局生效）。

**Tech Stack:** 现有认证骨架栈：React 18/19 + TS strict + Vite + antd v6 + react-router-dom v7 + zustand v5 + axios + vitest + @testing-library/react（测试基建已就位：globals cleanup、matchMedia/ResizeObserver mock）

**Spec:** `docs/design/2026-09-02-frontend-import-slice-design.md`

## Global Constraints

- 只改 `haowugou-web/` 下文件 + `docs/design/` 文档；不 `git add` 后端任何文件（工作区有后端未提交改动）
- 每个 commit 结尾加 `Co-Authored-By: Claude <noreply@anthropic.com>`；最后一步推送
- 中文 UI 文案；权限展示用 `canManage` 布尔，不按 roleId 推断；普通用户不渲染导入菜单与门店选择器
- 409/400 错误文案一律取后端 Problem Detail 的 `detail`（`getProblemDetailMessage`），不自行翻译
- 批次列表分页后端 0 基页码，antd 1 基——转换点集中在页面，API 层原样透传
- 设计 §5.4 的「管理员未选门店空状态」落地为**菜单项禁用 + 选择器「未选门店」提示**（业务页 URL 必带 storeId，未选门店时菜单不可达业务页，故无页面级空状态）
- 撤销成功提示须包含「同一文件可重新上传」（后端撤销释放坑位）

---

### Task 1: 门店 API 与选择状态 store

**Files:**
- Create: `haowugou-web/src/api/stores.ts`
- Create: `haowugou-web/src/stores/app.ts`
- Test: `haowugou-web/src/stores/app.test.ts`

**Interfaces:**
- Consumes: `src/api/types.ts` 的 `StoreView`；`src/api/http.ts` 默认导出 `http`
- Produces: `api/stores.ts` 导出 `listStores(): Promise<StoreView[]>`；`stores/app.ts` 导出 `useAppStore`（zustand）：状态 `stores: StoreView[]`、`currentStoreId: number | null`，action `loadStores(): Promise<void>`（失败静默置空）、`selectStore(id: number): void`、`clearStore(): void`——Task 2/4/7/8/9 使用

- [ ] **Step 1: 写失败测试**

新建 `haowugou-web/src/stores/app.test.ts`：

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { StoreView } from '../api/types';
import { useAppStore } from './app';

vi.mock('../api/stores', () => ({ listStores: vi.fn() }));

import * as storesApi from '../api/stores';

const stores: StoreView[] = [
  { id: 1, storeCode: 'S001', storeName: '门店一' },
  { id: 2, storeCode: 'S002', storeName: '门店二' },
];

describe('app store', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAppStore.setState({ stores: [], currentStoreId: null });
  });

  it('loadStores 成功后保存门店列表', async () => {
    vi.mocked(storesApi.listStores).mockResolvedValue(stores);
    await useAppStore.getState().loadStores();
    expect(useAppStore.getState().stores).toEqual(stores);
  });

  it('loadStores 失败时静默置空不抛错', async () => {
    vi.mocked(storesApi.listStores).mockRejectedValue(new Error('403'));
    await expect(useAppStore.getState().loadStores()).resolves.toBeUndefined();
    expect(useAppStore.getState().stores).toEqual([]);
  });

  it('selectStore 保存选中门店', () => {
    useAppStore.getState().selectStore(2);
    expect(useAppStore.getState().currentStoreId).toBe(2);
  });

  it('clearStore 清空选中与列表', () => {
    useAppStore.setState({ stores, currentStoreId: 1 });
    useAppStore.getState().clearStore();
    expect(useAppStore.getState().currentStoreId).toBeNull();
    expect(useAppStore.getState().stores).toEqual([]);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd haowugou-web && npm run test
```

Expected: FAIL，找不到 `./app` 与 `../api/stores` 模块。

- [ ] **Step 3: 实现**

新建 `haowugou-web/src/api/stores.ts`：

```ts
import http from './http';
import type { StoreView } from './types';

/** 全部已启用门店（后端仅管理员可用；普通用户从不调用）。 */
export async function listStores(): Promise<StoreView[]> {
  const { data } = await http.get<StoreView[]>('/api/stores');
  return data;
}
```

新建 `haowugou-web/src/stores/app.ts`：

```ts
import { create } from 'zustand';
import { listStores } from '../api/stores';
import type { StoreView } from '../api/types';

interface AppState {
  /** 门店列表（仅管理员加载）。 */
  stores: StoreView[];
  /** 管理员当前选中门店；普通用户恒为 null（从 profile.store 派生）。 */
  currentStoreId: number | null;
  loadStores: () => Promise<void>;
  selectStore: (id: number) => void;
  clearStore: () => void;
}

export const useAppStore = create<AppState>((set) => ({
  stores: [],
  currentStoreId: null,

  loadStores: async () => {
    try {
      set({ stores: await listStores() });
    } catch {
      // 失败静默置空：选择器展示空数据，不阻塞页面
      set({ stores: [] });
    }
  },

  selectStore: (id) => set({ currentStoreId: id }),

  clearStore: () => set({ currentStoreId: null, stores: [] }),
}));
```

- [ ] **Step 4: 跑测试确认通过**

Expected: PASS（4 条用例）。

- [ ] **Step 5: 提交**

```bash
git add haowugou-web/src/api/stores.ts haowugou-web/src/stores/app.ts haowugou-web/src/stores/app.test.ts
git commit -m "feat: 门店列表 API 与选择状态 store（含单测）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: 路由工具与门店同步钩子

**Files:**
- Create: `haowugou-web/src/router/path.ts`
- Create: `haowugou-web/src/hooks/useStoreSync.ts`
- Test: `haowugou-web/src/router/path.test.ts`、`haowugou-web/src/hooks/useStoreSync.test.tsx`

**Interfaces:**
- Consumes: Task 1 的 `useAppStore`
- Produces: `swapStoreIdInPath(pathname: string, storeId: number): string`（替换 `/stores/<旧id>/` 段；无该段时返回 `/stores/<id>/import-batches`）；`useStoreSync(storeId: number): void`（管理员进入业务页时把 URL 门店同步回 app store）——Task 3/4/7/8/9 使用

- [ ] **Step 1: 写失败测试**

新建 `haowugou-web/src/router/path.test.ts`：

```ts
import { describe, expect, it } from 'vitest';
import { swapStoreIdInPath } from './path';

describe('swapStoreIdInPath', () => {
  it('替换 /stores/ 段的 storeId，保留子路径', () => {
    expect(swapStoreIdInPath('/stores/1/import-batches', 2)).toBe('/stores/2/import-batches');
    expect(swapStoreIdInPath('/stores/1/imports/sales', 9)).toBe('/stores/9/imports/sales');
  });

  it('路径无 /stores/:id 段时跳到默认业务页', () => {
    expect(swapStoreIdInPath('/', 3)).toBe('/stores/3/import-batches');
    expect(swapStoreIdInPath('/login', 3)).toBe('/stores/3/import-batches');
  });
});
```

新建 `haowugou-web/src/hooks/useStoreSync.test.tsx`：

```tsx
import { renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { UserProfile } from '../api/types';
import { useAppStore } from '../stores/app';
import { useAuthStore } from '../stores/auth';
import { useStoreSync } from './useStoreSync';

const admin: UserProfile = {
  userId: 1, username: 'admin', displayName: '管理员', roleId: 1,
  role: 'ADMIN', store: null, canManage: true, canViewCostAndProfit: true,
};
const user: UserProfile = {
  userId: 2, username: 'store1user', displayName: '门店查询员', roleId: 2,
  role: 'USER', store: { id: 7, storeCode: 'S007', storeName: '门店七' },
  canManage: false, canViewCostAndProfit: false,
};

describe('useStoreSync', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ profile: admin });
    useAppStore.setState({ currentStoreId: null, stores: [] });
  });

  it('管理员进入业务页时把 URL 门店同步进 app store', () => {
    const { rerender } = renderHook(({ id }: { id: number }) => useStoreSync(id), {
      initialProps: { id: 5 },
    });
    expect(useAppStore.getState().currentStoreId).toBe(5);
    rerender({ id: 6 });
    expect(useAppStore.getState().currentStoreId).toBe(6);
  });

  it('普通用户不动 app store（门店来自 profile）', () => {
    useAuthStore.setState({ profile: user });
    renderHook(() => useStoreSync(3));
    expect(useAppStore.getState().currentStoreId).toBeNull();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Expected: FAIL，找不到模块。

- [ ] **Step 3: 实现**

新建 `haowugou-web/src/router/path.ts`：

```ts
/**
 * 把路径里的 /stores/<id>/ 段换成新门店；路径没有该段（如首页）时
 * 跳到默认业务页「导入批次」。
 */
export function swapStoreIdInPath(pathname: string, storeId: number): string {
  const replaced = pathname.replace(/^\/stores\/\d+(?=\/|$)/, `/stores/${storeId}`);
  return replaced === pathname ? `/stores/${storeId}/import-batches` : replaced;
}
```

新建 `haowugou-web/src/hooks/useStoreSync.ts`：

```ts
import { useEffect } from 'react';
import { useAppStore } from '../stores/app';
import { useAuthStore } from '../stores/auth';

/**
 * 管理员进入业务页时把 URL 里的门店同步进 app store（选择器与菜单随 URL 显示）。
 * 普通用户门店来自 profile，不动 app store。
 */
export function useStoreSync(storeId: number): void {
  const isAdmin = useAuthStore((s) => s.profile?.store == null);
  const currentStoreId = useAppStore((s) => s.currentStoreId);
  const selectStore = useAppStore((s) => s.selectStore);

  useEffect(() => {
    if (isAdmin && currentStoreId !== storeId) {
      selectStore(storeId);
    }
  }, [isAdmin, currentStoreId, storeId, selectStore]);
}
```

- [ ] **Step 4: 跑测试确认通过**

Expected: PASS（新增 4 条）。

- [ ] **Step 5: 提交**

```bash
git add haowugou-web/src/router/path.ts haowugou-web/src/router/path.test.ts haowugou-web/src/hooks/useStoreSync.ts haowugou-web/src/hooks/useStoreSync.test.tsx
git commit -m "feat: 路由门店替换工具与门店同步钩子（含单测）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: 业务路由与门店范围守卫

**Files:**
- Modify: `haowugou-web/src/router/index.tsx`（加 `/stores/:storeId/` 子树与 `storeScopedLoader`）
- Create: `haowugou-web/src/pages/imports/ImportBatchesPage.tsx`、`src/pages/imports/InventoryImportPage.tsx`、`src/pages/imports/SalesImportPage.tsx`（占位，Task 7/8/9 替换）
- Test: `haowugou-web/src/router/storeScopedLoader.test.tsx`

**Interfaces:**
- Consumes: Task 1 `useAppStore`、Task 2 `swapStoreIdInPath`、现有 `useAuthStore`
- Produces: `storeScopedLoader({ request, params }): Promise<null | Response>`（Task 3 挂到 `/stores/:storeId` 路由上；页面经 `useParams` 读 storeId）

- [ ] **Step 1: 写失败测试**

新建 `haowugou-web/src/router/storeScopedLoader.test.tsx`：

```tsx
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { UserProfile } from '../api/types';
import { useAuthStore } from '../stores/auth';
import { storeScopedLoader } from './index';

vi.mock('../api/auth', () => ({ fetchMe: vi.fn() }));

import * as authApi from '../api/auth';

const admin: UserProfile = {
  userId: 1, username: 'admin', displayName: '管理员', roleId: 1,
  role: 'ADMIN', store: null, canManage: true, canViewCostAndProfit: true,
};
const user: UserProfile = {
  userId: 2, username: 'store1user', displayName: '门店查询员', roleId: 2,
  role: 'USER', store: { id: 7, storeCode: 'S007', storeName: '门店七' },
  canManage: false, canViewCostAndProfit: false,
};

function loaderArgs(pathname: string, storeId: string) {
  return {
    request: new Request(`http://localhost${pathname}`),
    params: { storeId },
  };
}

describe('storeScopedLoader', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ profile: null });
  });

  it('管理员放行', async () => {
    useAuthStore.setState({ profile: admin });
    expect(await storeScopedLoader(loaderArgs('/stores/5/import-batches', '5'))).toBeNull();
  });

  it('普通用户访问自家门店放行', async () => {
    useAuthStore.setState({ profile: user });
    expect(await storeScopedLoader(loaderArgs('/stores/7/import-batches', '7'))).toBeNull();
  });

  it('普通用户访问别家门店重定向到自家', async () => {
    useAuthStore.setState({ profile: user });
    const result = await storeScopedLoader(loaderArgs('/stores/3/import-batches', '3'));
    expect(result).not.toBeNull();
    expect((result as Response).headers.get('Location')).toBe('/stores/7/import-batches');
  });

  it('无登录态时恢复会话后放行', async () => {
    vi.mocked(authApi.fetchMe).mockResolvedValue(user);
    expect(await storeScopedLoader(loaderArgs('/stores/7/imports/sales', '7'))).toBeNull();
  });

  it('无登录态且 me 401 时重定向登录页', async () => {
    vi.mocked(authApi.fetchMe).mockRejectedValue(new Error('401'));
    const result = await storeScopedLoader(loaderArgs('/stores/7/import-batches', '7'));
    expect((result as Response).headers.get('Location')).toBe('/login');
  });
});
```

注：与 `protectedLoader` 测试同样的坑——`fetchMe` mock 会被 `useAuthStore.setState` 保留的旧 action 覆盖，本测试不 setState 替换 action，直接 mock `../api/auth` 的 `fetchMe` 走真实 store action。

- [ ] **Step 2: 跑测试确认失败**

Expected: FAIL，`storeScopedLoader` 未导出。

- [ ] **Step 3: 实现守卫与路由**

改写 `haowugou-web/src/router/index.tsx`（整体替换）：

```tsx
import { createBrowserRouter, redirect } from 'react-router-dom';
import MainLayout from '../layouts/MainLayout';
import LoginPage from '../pages/LoginPage';
import HomePage from '../pages/HomePage';
import ImportBatchesPage from '../pages/imports/ImportBatchesPage';
import InventoryImportPage from '../pages/imports/InventoryImportPage';
import SalesImportPage from '../pages/imports/SalesImportPage';
import { useAuthStore } from '../stores/auth';
import { swapStoreIdInPath } from './path';

interface LoaderArgs {
  request: Request;
  params: { storeId?: string };
}

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

/**
 * 门店范围路由的 loader：确保登录态后，普通用户访问别家门店时
 * 重定向到自家门店同路径。管理员不限制（门店由选择器决定）。
 */
export async function storeScopedLoader({ request, params }: LoaderArgs): Promise<null | Response> {
  const store = useAuthStore.getState();
  if (!store.profile) {
    try {
      await store.fetchMe();
    } catch {
      return redirect('/login');
    }
  }
  const profile = useAuthStore.getState().profile!;
  if (profile.store && params.storeId !== String(profile.store.id)) {
    const pathname = new URL(request.url).pathname;
    return redirect(swapStoreIdInPath(pathname, profile.store.id));
  }
  return null;
}

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: <MainLayout />,
    loader: protectedLoader,
    children: [
      { index: true, element: <HomePage /> },
      {
        path: '/stores/:storeId',
        loader: storeScopedLoader,
        children: [
          { path: 'imports/inventory', element: <InventoryImportPage /> },
          { path: 'imports/sales', element: <SalesImportPage /> },
          { path: 'import-batches', element: <ImportBatchesPage /> },
        ],
      },
    ],
  },
]);
```

创建三个占位页面（Task 7/8/9 替换），各文件内容同构（以 ImportBatchesPage 为例）：

```tsx
export default function ImportBatchesPage() {
  return <div>ImportBatchesPage</div>;
}
```

`InventoryImportPage.tsx` / `SalesImportPage.tsx` 同构占位。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd haowugou-web && npm run test && npm run build
```

Expected: 全量 PASS（新增 5 条）+ 构建无 TS 报错。

- [ ] **Step 5: 提交**

```bash
git add haowugou-web/src/router haowugou-web/src/pages/imports
git commit -m "feat: 业务路由与门店范围守卫（含单测）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: 主布局重构（侧边菜单 + 门店选择器）

**Files:**
- Modify: `haowugou-web/src/layouts/MainLayout.tsx`（整体替换）
- Create: `haowugou-web/src/layouts/StoreSelector.tsx`
- Test: 更新 `haowugou-web/src/layouts/MainLayout.test.tsx`（既有 2 条用例保持断言不变）+ 新建 `haowugou-web/src/layouts/StoreSelector.test.tsx`

**Interfaces:**
- Consumes: Task 1 `useAppStore`、Task 2 `swapStoreIdInPath`、`useAuthStore`
- Produces: `MainLayout`（Sider 菜单仅 `canManage` 可见；Header 含 `StoreSelector`）；`StoreSelector`（仅管理员渲染）

- [ ] **Step 1: 写失败测试**

更新 `haowugou-web/src/layouts/MainLayout.test.tsx`，在文件末尾 describe 内追加两条用例（文件头与既有内容不变）：

```tsx
  it('普通用户看不到导入菜单', () => {
    renderLayout();
    expect(screen.queryByText('导入管理')).not.toBeInTheDocument();
  });

  it('管理员看到导入菜单，未选门店时菜单项禁用', () => {
    useAuthStore.setState({
      profile: {
        userId: 1, username: 'admin', displayName: '管理员', roleId: 1,
        role: 'ADMIN', store: null, canManage: true, canViewCostAndProfit: true,
      },
    });
    useAppStore.setState({ stores: [], currentStoreId: null });
    renderLayout();
    expect(screen.getByText('导入管理')).toBeInTheDocument();
    expect(screen.getByText('初始库存导入').closest('li')).toHaveClass('ant-menu-item-disabled');
  });
```

需在 MainLayout.test.tsx 头部补充 import：`import { useAppStore } from '../stores/app';`，并在 beforeEach 里加 `useAppStore.setState({ stores: [], currentStoreId: null });`，且 mock `../api/stores`：

```ts
vi.mock('../api/stores', () => ({ listStores: vi.fn().mockResolvedValue([]) }));
```

新建 `haowugou-web/src/layouts/StoreSelector.test.tsx`：

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider, useParams } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { StoreView, UserProfile } from '../api/types';
import { useAppStore } from '../stores/app';
import { useAuthStore } from '../stores/auth';
import StoreSelector from './StoreSelector';

vi.mock('../api/stores', () => ({ listStores: vi.fn().mockResolvedValue([]) }));

const admin: UserProfile = {
  userId: 1, username: 'admin', displayName: '管理员', roleId: 1,
  role: 'ADMIN', store: null, canManage: true, canViewCostAndProfit: true,
};
const stores: StoreView[] = [
  { id: 1, storeCode: 'S001', storeName: '门店一' },
  { id: 2, storeCode: 'S002', storeName: '门店二' },
];

function StoreIdProbe() {
  const { storeId } = useParams();
  return <div>storeId={storeId}</div>;
}

function renderSelector(initialPath = '/stores/1/import-batches') {
  const router = createMemoryRouter(
    [
      { path: '/', element: <><StoreSelector /><div>首页</div></> },
      { path: '/stores/:storeId/import-batches', element: <><StoreSelector /><StoreIdProbe /></> },
      { path: '/stores/:storeId/imports/sales', element: <><StoreSelector /><StoreIdProbe /></> },
    ],
    { initialEntries: [initialPath] },
  );
  render(<RouterProvider router={router} />);
}

describe('StoreSelector', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ profile: admin });
    useAppStore.setState({ stores, currentStoreId: 1 });
  });

  it('管理员看到选择器并展示当前门店', () => {
    renderSelector();
    expect(screen.getByText('门店一')).toBeInTheDocument();
  });

  it('切换门店后选择状态更新并导航到新门店路径', async () => {
    renderSelector();
    await userEvent.click(screen.getByText('门店一'));
    await userEvent.click(await screen.findByText('门店二'));
    expect(useAppStore.getState().currentStoreId).toBe(2);
    expect(await screen.findByText('storeId=2')).toBeInTheDocument();
  });

  it('普通用户不渲染选择器', () => {
    useAuthStore.setState({
      profile: {
        userId: 2, username: 'store1user', displayName: '门店查询员', roleId: 2,
        role: 'USER', store: { id: 1, storeCode: 'S001', storeName: '门店一' },
        canManage: false, canViewCostAndProfit: false,
      },
    });
    const { container } = render(
      <div>
        <StoreSelector />
      </div>,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Expected: FAIL（旧 MainLayout 无菜单与选择器）。

- [ ] **Step 3: 实现 StoreSelector**

新建 `haowugou-web/src/layouts/StoreSelector.tsx`：

```tsx
import { Select } from 'antd';
import { useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { swapStoreIdInPath } from '../router/path';
import { useAppStore } from '../stores/app';
import { useAuthStore } from '../stores/auth';

/** 顶栏门店选择器：仅管理员渲染；切换时改写 URL 的 storeId 段。 */
export default function StoreSelector() {
  const navigate = useNavigate();
  const location = useLocation();
  const profile = useAuthStore((s) => s.profile);
  const stores = useAppStore((s) => s.stores);
  const currentStoreId = useAppStore((s) => s.currentStoreId);
  const loadStores = useAppStore((s) => s.loadStores);
  const selectStore = useAppStore((s) => s.selectStore);

  useEffect(() => {
    if (profile && !profile.store) {
      void loadStores();
    }
  }, [profile, loadStores]);

  if (!profile || profile.store) {
    return null;
  }

  return (
    <Select
      style={{ width: 220 }}
      placeholder="未选门店"
      value={currentStoreId ?? undefined}
      options={stores.map((s) => ({ value: s.id, label: s.storeName }))}
      onChange={(id) => {
        selectStore(id);
        navigate(swapStoreIdInPath(location.pathname, id));
      }}
    />
  );
}
```

- [ ] **Step 4: 实现 MainLayout**

改写 `haowugou-web/src/layouts/MainLayout.tsx`（整体替换）：

```tsx
import { LogoutOutlined, ShoppingOutlined, UserOutlined } from '@ant-design/icons';
import { Avatar, Dropdown, Layout, Menu, Space, Tag, Typography } from 'antd';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAppStore } from '../stores/app';
import { useAuthStore } from '../stores/auth';
import StoreSelector from './StoreSelector';

export default function MainLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const profile = useAuthStore((s) => s.profile);
  const logout = useAuthStore((s) => s.logout);
  const currentStoreId = useAppStore((s) => s.currentStoreId);
  const clearStore = useAppStore((s) => s.clearStore);

  // 业务页菜单：普通用户不可见；管理员未选门店时禁用
  const canManage = profile?.canManage ?? false;
  const base = profile?.store ? `/stores/${profile.store.id}` : currentStoreId ? `/stores/${currentStoreId}` : null;

  const onLogout = async () => {
    try {
      await logout();
    } finally {
      clearStore();
      navigate('/login', { replace: true });
    }
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Header
        style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16 }}
      >
        <Space>
          <ShoppingOutlined style={{ color: '#fff', fontSize: 20 }} />
          <Typography.Title level={4} style={{ color: '#fff', margin: 0 }}>
            好物购
          </Typography.Title>
        </Space>
        <Space>
          <StoreSelector />
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
        </Space>
      </Layout.Header>
      <Layout>
        {canManage && (
          <Layout.Sider theme="light" width={200}>
            <Menu
              mode="inline"
              selectedKeys={[location.pathname]}
              items={[
                {
                  type: 'group',
                  label: '导入管理',
                  children: [
                    {
                      key: `${base ?? ''}/imports/inventory`,
                      label: '初始库存导入',
                      disabled: !base,
                    },
                    {
                      key: `${base ?? ''}/imports/sales`,
                      label: '每日销售导入',
                      disabled: !base,
                    },
                    {
                      key: `${base ?? ''}/import-batches`,
                      label: '导入批次',
                      disabled: !base,
                    },
                  ],
                },
              ]}
              onClick={({ key }) => navigate(key)}
            />
          </Layout.Sider>
        )}
        <Layout.Content style={{ padding: 24 }}>
          <Outlet />
        </Layout.Content>
      </Layout>
    </Layout>
  );
}
```

注：`selectedKeys` 用 `location.pathname` 与 item key（完整路径）匹配；无选中项时为空数组，antd 不报错。

- [ ] **Step 5: 跑测试确认通过**

```bash
cd haowugou-web && npm run test && npm run build
```

Expected: 全量 PASS + 构建无 TS 报错。若 antd v6 报 `Space` 或其他废弃警告，照 v6 迁移口径修（不引入新依赖）。

- [ ] **Step 6: 提交**

```bash
git add haowugou-web/src/layouts
git commit -m "feat: 主布局侧边菜单与门店选择器（含单测）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: 导入 API 层

**Files:**
- Create: `haowugou-web/src/api/imports.ts`
- Test: `haowugou-web/src/api/imports.test.ts`

**Interfaces:**
- Consumes: `http` 默认导出；`types.ts` 的 `StoreView`
- Produces: 类型 `ImportType`、`ImportBatchStatus`、`ImportResultStatus`、`ImportBatchItem`、`ImportBatchPage`、`ImportBatchProblemRow`、`ImportBatchDetail`、`WarehouseView`、`RowError`、`ImportResult`、`ReverseResult`、`ListBatchesCriteria`；函数 `listBatches(storeId, criteria)`、`getBatch(storeId, batchId, page, size)`、`reverseBatch(storeId, batchId, body)`、`importInventory(storeId, file, warehouseId?)`、`importDailySales(storeId, file, businessDate)`、`listWarehouses(storeId)`——Task 6/7/8/9 使用

- [ ] **Step 1: 写失败测试**

新建 `haowugou-web/src/api/imports.test.ts`：

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as imports from './imports';

const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));

vi.mock('./http', () => ({ default: { get: http.get, post: http.post } }));

describe('imports api', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('listBatches 传完整筛选参数', async () => {
    http.get.mockResolvedValue({ data: {} });
    await imports.listBatches(5, {
      importType: 'DAILY_SALES',
      status: 'POSTED',
      dataDateFrom: '2026-08-01',
      dataDateTo: '2026-08-31',
      page: 2,
      size: 10,
    });
    expect(http.get).toHaveBeenCalledWith('/api/stores/5/import-batches', {
      params: {
        importType: 'DAILY_SALES',
        status: 'POSTED',
        dataDateFrom: '2026-08-01',
        dataDateTo: '2026-08-31',
        page: 2,
        size: 10,
      },
    });
  });

  it('listBatches 空筛选只传分页参数', async () => {
    http.get.mockResolvedValue({ data: {} });
    await imports.listBatches(5, { page: 0, size: 20 });
    expect(http.get).toHaveBeenCalledWith('/api/stores/5/import-batches', {
      params: { page: 0, size: 20 },
    });
  });

  it('getBatch 传问题行分页参数', async () => {
    http.get.mockResolvedValue({ data: {} });
    await imports.getBatch(5, 42, 1, 30);
    expect(http.get).toHaveBeenCalledWith('/api/stores/5/import-batches/42', {
      params: { page: 1, size: 30 },
    });
  });

  it('reverseBatch 提交操作人与原因', async () => {
    http.post.mockResolvedValue({ data: {} });
    await imports.reverseBatch(5, 42, { reversedBy: '管理员', reversedReason: '数据日期填错' });
    expect(http.post).toHaveBeenCalledWith(
      '/api/stores/5/import-batches/42/reverse',
      { reversedBy: '管理员', reversedReason: '数据日期填错' },
    );
  });

  it('importInventory 构建 FormData（文件 + 仓库）', async () => {
    http.post.mockResolvedValue({ data: {} });
    const file = new File(['x'], 'a.xlsx');
    await imports.importInventory(5, file, 9);
    const [, form] = http.post.mock.calls[0] as [string, FormData];
    expect(http.post.mock.calls[0][0]).toBe('/api/stores/5/inventory/import');
    expect(form.get('file')).toBe(file);
    expect(form.get('warehouseId')).toBe('9');
  });

  it('importInventory 不传仓库时 FormData 无 warehouseId', async () => {
    http.post.mockResolvedValue({ data: {} });
    await imports.importInventory(5, new File(['x'], 'a.xlsx'));
    const form = http.post.mock.calls[0][1] as FormData;
    expect(form.get('warehouseId')).toBeNull();
  });

  it('importDailySales 构建 FormData（文件 + 业务日期）', async () => {
    http.post.mockResolvedValue({ data: {} });
    const file = new File(['x'], 'a.xls');
    await imports.importDailySales(5, file, '2026-09-01');
    const [url, form] = http.post.mock.calls[0] as [string, FormData];
    expect(url).toBe('/api/stores/5/sales/import');
    expect(form.get('file')).toBe(file);
    expect(form.get('businessDate')).toBe('2026-09-01');
  });

  it('listWarehouses 请求门店仓库', async () => {
    http.get.mockResolvedValue({ data: [] });
    await imports.listWarehouses(5);
    expect(http.get).toHaveBeenCalledWith('/api/stores/5/warehouses');
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Expected: FAIL，找不到 `./imports`。

- [ ] **Step 3: 实现**

新建 `haowugou-web/src/api/imports.ts`：

```ts
import http from './http';
import type { StoreView } from './types';

export type ImportType = 'INITIAL_INVENTORY' | 'DAILY_SALES';
export type ImportBatchStatus = 'VALIDATING' | 'POSTING' | 'POSTED' | 'REVERSED' | 'FAILED';
export type ImportResultStatus = 'POSTED' | 'FAILED';

export interface ImportBatchItem {
  batchId: number;
  importType: ImportType;
  status: ImportBatchStatus;
  dataDate: string | null;
  fileName: string;
  totalRows: number;
  successRows: number;
  errorRows: number;
  importedAt: string;
  postedAt: string | null;
  reversedAt: string | null;
  reversible: boolean;
}

export interface ImportBatchPage {
  store: StoreView;
  items: ImportBatchItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ImportBatchProblemRow {
  rowNumber: number;
  barcode: string | null;
  parseStatus: string;
  errorMessage: string;
}

export interface ImportBatchDetail {
  store: StoreView;
  batch: {
    batchId: number;
    importType: ImportType;
    status: ImportBatchStatus;
    dataDate: string | null;
    fileName: string;
    fileHash: string;
    totalRows: number;
    successRows: number;
    errorRows: number;
    errorMessage: string | null;
    operatorName: string;
    importedAt: string;
    postedAt: string | null;
    reversedAt: string | null;
    reversedBy: string | null;
    reversedReason: string | null;
    reversible: boolean;
  };
  problemRows: {
    items: ImportBatchProblemRow[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
  };
}

export interface WarehouseView {
  id: number;
  storeId: number;
  warehouseCode: string | null;
  warehouseName: string;
}

export interface RowError {
  rowNumber: number;
  barcode: string | null;
  message: string;
}

export interface ImportResult {
  batchId: number;
  status: ImportResultStatus;
  totalRows: number;
  successRows: number;
  errorRows: number;
  salesRows?: number;
  pendingProductsCreated?: number;
  deductedProducts?: number;
  errors: RowError[];
}

export interface ReverseResult {
  store: StoreView;
  batchId: number;
  importType: ImportType;
  dataDate: string | null;
  fileName: string;
  reversedMovements: number;
  restoredProducts: number;
  reversedAt: string;
  reversedBy: string;
  reversedReason: string;
}

export interface ListBatchesCriteria {
  importType?: ImportType;
  status?: ImportBatchStatus;
  dataDateFrom?: string;
  dataDateTo?: string;
  page: number;
  size: number;
}

export async function listBatches(
  storeId: number,
  criteria: ListBatchesCriteria,
): Promise<ImportBatchPage> {
  const { data } = await http.get<ImportBatchPage>(`/api/stores/${storeId}/import-batches`, {
    params: criteria,
  });
  return data;
}

export async function getBatch(
  storeId: number,
  batchId: number,
  page: number,
  size: number,
): Promise<ImportBatchDetail> {
  const { data } = await http.get<ImportBatchDetail>(
    `/api/stores/${storeId}/import-batches/${batchId}`,
    { params: { page, size } },
  );
  return data;
}

export async function reverseBatch(
  storeId: number,
  batchId: number,
  body: { reversedBy: string; reversedReason: string },
): Promise<ReverseResult> {
  const { data } = await http.post<ReverseResult>(
    `/api/stores/${storeId}/import-batches/${batchId}/reverse`,
    body,
  );
  return data;
}

/** 初始库存导入（multipart）；不手工设 Content-Type，axios 自动带 boundary。 */
export async function importInventory(
  storeId: number,
  file: File,
  warehouseId?: number,
): Promise<ImportResult> {
  const form = new FormData();
  form.append('file', file);
  if (warehouseId != null) {
    form.append('warehouseId', String(warehouseId));
  }
  const { data } = await http.post<ImportResult>(`/api/stores/${storeId}/inventory/import`, form);
  return data;
}

/** 每日销售导入（multipart）；businessDate 必填（POS 文件无日期列）。 */
export async function importDailySales(
  storeId: number,
  file: File,
  businessDate: string,
): Promise<ImportResult> {
  const form = new FormData();
  form.append('file', file);
  form.append('businessDate', businessDate);
  const { data } = await http.post<ImportResult>(`/api/stores/${storeId}/sales/import`, form);
  return data;
}

export async function listWarehouses(storeId: number): Promise<WarehouseView[]> {
  const { data } = await http.get<WarehouseView[]>(`/api/stores/${storeId}/warehouses`);
  return data;
}
```

- [ ] **Step 4: 跑测试确认通过**

Expected: PASS（8 条用例）。

- [ ] **Step 5: 提交**

```bash
git add haowugou-web/src/api/imports.ts haowugou-web/src/api/imports.test.ts
git commit -m "feat: 导入 API 层（批次/撤销/上传/仓库，含单测）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: 批次详情抽屉（含撤销流程）

**Files:**
- Create: `haowugou-web/src/pages/imports/BatchDetailDrawer.tsx`
- Test: `haowugou-web/src/pages/imports/BatchDetailDrawer.test.tsx`

**Interfaces:**
- Consumes: Task 5 `getBatch`、`reverseBatch`、类型；`getProblemDetailMessage`；`useAuthStore.profile.displayName`
- Produces: `BatchDetailDrawer` props `{ storeId: number; batchId: number | null; open: boolean; onClose: () => void; onReversed: () => void }`——Task 7 使用

- [ ] **Step 1: 写失败测试**

新建 `haowugou-web/src/pages/imports/BatchDetailDrawer.test.tsx`：

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { UserProfile } from '../../api/types';
import { useAuthStore } from '../../stores/auth';
import BatchDetailDrawer from './BatchDetailDrawer';

vi.mock('../../api/imports', () => ({
  getBatch: vi.fn(),
  reverseBatch: vi.fn(),
}));

vi.mock('../../api/http', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/http')>();
  return { ...actual, getProblemDetailMessage: vi.fn() };
});

import * as importsApi from '../../api/imports';
import { getProblemDetailMessage } from '../../api/http';

const admin: UserProfile = {
  userId: 1, username: 'admin', displayName: '管理员', roleId: 1,
  role: 'ADMIN', store: null, canManage: true, canViewCostAndProfit: true,
};

const detail = {
  store: { id: 5, storeCode: 'S005', storeName: '门店五' },
  batch: {
    batchId: 42,
    importType: 'DAILY_SALES',
    status: 'POSTED',
    dataDate: '2026-08-30',
    fileName: 'sales.xlsx',
    fileHash: 'a'.repeat(64),
    totalRows: 10,
    successRows: 10,
    errorRows: 0,
    errorMessage: null,
    operatorName: '管理员',
    importedAt: '2026-08-30T10:00:00',
    postedAt: '2026-08-30T10:00:01',
    reversedAt: null,
    reversedBy: null,
    reversedReason: null,
    reversible: true,
  },
  problemRows: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
};

function renderDrawer(batch: typeof detail = detail) {
  vi.mocked(importsApi.getBatch).mockResolvedValue(batch);
  const onClose = vi.fn();
  const onReversed = vi.fn();
  render(
    <BatchDetailDrawer
      storeId={5}
      batchId={42}
      open={true}
      onClose={onClose}
      onReversed={onReversed}
    />,
  );
  return { onClose, onReversed };
}

describe('BatchDetailDrawer', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ profile: admin });
  });

  it('加载并展示批次元信息', async () => {
    renderDrawer();
    expect(await screen.findByText('sales.xlsx')).toBeInTheDocument();
    expect(screen.getByText('2026-08-30')).toBeInTheDocument();
    expect(importsApi.getBatch).toHaveBeenCalledWith(5, 42, 0, 20);
  });

  it('可撤销批次点击撤销：默认操作人为当前用户、原因必填', async () => {
    vi.mocked(importsApi.reverseBatch).mockResolvedValue({
      store: { id: 5, storeCode: 'S005', storeName: '门店五' },
      batchId: 42, importType: 'DAILY_SALES', dataDate: '2026-08-30',
      fileName: 'sales.xlsx', reversedMovements: 10, restoredProducts: 8,
      reversedAt: '2026-09-01T09:00:00', reversedBy: '管理员', reversedReason: '填错日期',
    });
    const { onReversed } = renderDrawer();

    await userEvent.click(await screen.findByRole('button', { name: /撤销批次/ }));
    // 弹窗内默认操作人
    const operatorInput = await screen.findByDisplayValue('管理员');
    expect(operatorInput).toBeInTheDocument();

    await userEvent.type(screen.getByPlaceholderText('撤销原因'), '填错日期');
    await userEvent.click(screen.getByRole('button', { name: /确认撤销/ }));

    await waitFor(() => expect(importsApi.reverseBatch).toHaveBeenCalled());
    expect(importsApi.reverseBatch).toHaveBeenCalledWith(5, 42, {
      reversedBy: '管理员',
      reversedReason: '填错日期',
    });
    await waitFor(() => expect(onReversed).toHaveBeenCalled());
  });

  it('撤销冲突（409）时弹窗内展示后端文案', async () => {
    vi.mocked(importsApi.reverseBatch).mockRejectedValue(new Error('409'));
    vi.mocked(getProblemDetailMessage).mockReturnValue('批次不可撤销');
    const { onReversed } = renderDrawer();

    await userEvent.click(await screen.findByRole('button', { name: /撤销批次/ }));
    await userEvent.type(await screen.findByPlaceholderText('撤销原因'), '测试');
    await userEvent.click(screen.getByRole('button', { name: /确认撤销/ }));

    expect(await screen.findByText('批次不可撤销')).toBeInTheDocument();
    expect(onReversed).not.toHaveBeenCalled();
  });

  it('不可撤销批次不渲染撤销按钮', async () => {
    renderDrawer({
      ...detail,
      batch: { ...detail.batch, status: 'FAILED', reversible: false },
    });
    await screen.findByText('sales.xlsx');
    expect(screen.queryByRole('button', { name: /撤销批次/ })).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Expected: FAIL，找不到 `./BatchDetailDrawer`。

- [ ] **Step 3: 实现**

新建 `haowugou-web/src/pages/imports/BatchDetailDrawer.tsx`：

```tsx
import { Alert, Button, Descriptions, Drawer, Form, Input, Modal, Spin, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import {
  getBatch,
  reverseBatch,
  type ImportBatchDetail,
} from '../../api/imports';
import { getProblemDetailMessage } from '../../api/http';
import { useAuthStore } from '../../stores/auth';

const STATUS_COLOR: Record<string, string> = {
  POSTED: 'green',
  REVERSED: 'default',
  FAILED: 'red',
  VALIDATING: 'blue',
  POSTING: 'gold',
};

const TYPE_LABEL: Record<string, string> = {
  INITIAL_INVENTORY: '初始库存',
  DAILY_SALES: '每日销售',
};

const PARSE_STATUS_COLOR: Record<string, string> = {
  INVALID: 'red',
  WARNING: 'gold',
  PENDING: 'blue',
};

interface Props {
  storeId: number;
  batchId: number | null;
  open: boolean;
  onClose: () => void;
  onReversed: () => void;
}

export default function BatchDetailDrawer({ storeId, batchId, open, onClose, onReversed }: Props) {
  const profile = useAuthStore((s) => s.profile);
  const [detail, setDetail] = useState<ImportBatchDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [problemPage, setProblemPage] = useState(0);
  const [reverseOpen, setReverseOpen] = useState(false);
  const [reversing, setReversing] = useState(false);
  const [reverseError, setReverseError] = useState<string | null>(null);
  const [reverseForm] = Form.useForm<{ reversedBy: string; reversedReason: string }>();

  const load = useCallback(async () => {
    if (batchId == null) {
      return;
    }
    setLoading(true);
    try {
      setDetail(await getBatch(storeId, batchId, problemPage, 20));
    } finally {
      setLoading(false);
    }
  }, [storeId, batchId, problemPage]);

  useEffect(() => {
    if (open) {
      setProblemPage(0);
      setReverseOpen(false);
      setReverseError(null);
      void load();
    }
  }, [open, load]);

  const openReverse = () => {
    setReverseError(null);
    reverseForm.setFieldsValue({ reversedBy: profile?.displayName ?? '', reversedReason: '' });
    setReverseOpen(true);
  };

  const onReverse = async () => {
    if (batchId == null) {
      return;
    }
    const values = await reverseForm.validateFields();
    setReversing(true);
    setReverseError(null);
    try {
      const result = await reverseBatch(storeId, batchId, values);
      setReverseOpen(false);
      void Modal.success({
        title: '批次已撤销',
        content: `回滚库存商品 ${result.restoredProducts} 个、反向流水 ${result.reversedMovements} 条。同一文件可重新上传。`,
      });
      onReversed();
    } catch (e) {
      setReverseError(getProblemDetailMessage(e) ?? '撤销失败，请稍后重试');
    } finally {
      setReversing(false);
    }
  };

  const batch = detail?.batch;

  return (
    <>
      <Drawer
        title={`批次详情 #${batchId ?? ''}`}
        width={720}
        open={open}
        onClose={onClose}
        extra={
          batch?.reversible && (
            <Button type="primary" danger onClick={openReverse}>
              撤销批次
            </Button>
          )
        }
      >
        <Spin spinning={loading}>
          {batch && detail && (
            <>
              <Descriptions column={2} bordered size="small">
                <Descriptions.Item label="类型">
                  <Tag>{TYPE_LABEL[batch.importType] ?? batch.importType}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label="状态">
                  <Tag color={STATUS_COLOR[batch.status]}>{batch.status}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label="数据日期">{batch.dataDate ?? '—'}</Descriptions.Item>
                <Descriptions.Item label="文件名">{batch.fileName}</Descriptions.Item>
                <Descriptions.Item label="行数" span={2}>
                  总 {batch.totalRows} / 成功 {batch.successRows} / 错误 {batch.errorRows}
                </Descriptions.Item>
                <Descriptions.Item label="操作人">{batch.operatorName}</Descriptions.Item>
                <Descriptions.Item label="上传时间">{batch.importedAt}</Descriptions.Item>
                <Descriptions.Item label="入账时间">{batch.postedAt ?? '—'}</Descriptions.Item>
                <Descriptions.Item label="撤销时间">{batch.reversedAt ?? '—'}</Descriptions.Item>
                <Descriptions.Item label="文件指纹" span={2}>
                  <Typography.Text copyable style={{ fontSize: 12 }} ellipsis>
                    {batch.fileHash}
                  </Typography.Text>
                </Descriptions.Item>
                {batch.errorMessage && (
                  <Descriptions.Item label="批次错误" span={2}>
                    <Typography.Text type="danger">{batch.errorMessage}</Typography.Text>
                  </Descriptions.Item>
                )}
                {batch.reversedBy && (
                  <>
                    <Descriptions.Item label="撤销人">{batch.reversedBy}</Descriptions.Item>
                    <Descriptions.Item label="撤销原因">{batch.reversedReason ?? '—'}</Descriptions.Item>
                  </>
                )}
              </Descriptions>

              <Typography.Title level={5} style={{ marginTop: 24 }}>
                问题行（{detail.problemRows.totalElements}）
              </Typography.Title>
              <Table
                rowKey="rowNumber"
                size="small"
                dataSource={detail.problemRows.items}
                pagination={{
                  current: detail.problemRows.page + 1,
                  pageSize: detail.problemRows.size,
                  total: detail.problemRows.totalElements,
                  onChange: (p) => setProblemPage(p - 1),
                }}
                columns={[
                  { title: 'Excel 行号', dataIndex: 'rowNumber', width: 110 },
                  { title: '条码', dataIndex: 'barcode', render: (v: string | null) => v ?? '—' },
                  {
                    title: '解析状态',
                    dataIndex: 'parseStatus',
                    width: 110,
                    render: (v: string) => <Tag color={PARSE_STATUS_COLOR[v]}>{v}</Tag>,
                  },
                  { title: '错误信息', dataIndex: 'errorMessage' },
                ]}
              />
            </>
          )}
        </Spin>
      </Drawer>

      <Modal
        title="撤销批次"
        open={reverseOpen}
        onOk={onReverse}
        onCancel={() => setReverseOpen(false)}
        confirmLoading={reversing}
        okText="确认撤销"
      >
        <Form form={reverseForm} layout="vertical">
          <Form.Item
            label="操作人"
            name="reversedBy"
            rules={[{ required: true, message: '请输入操作人' }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            label="撤销原因"
            name="reversedReason"
            rules={[{ required: true, message: '请输入撤销原因' }]}
          >
            <Input.TextArea placeholder="撤销原因" rows={3} />
          </Form.Item>
          {reverseError && <Alert type="error" title={reverseError} showIcon />}
        </Form>
      </Modal>
    </>
  );
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd haowugou-web && npm run test
```

Expected: PASS（4 条用例）。若 antd v6 的 Modal/Drawer 渲染细节与用例定位不符（如按钮渲染进 portal），按 v6 API 调整实现并保持用例断言语义不变。

- [ ] **Step 5: 提交**

```bash
git add haowugou-web/src/pages/imports/BatchDetailDrawer.tsx haowugou-web/src/pages/imports/BatchDetailDrawer.test.tsx
git commit -m "feat: 批次详情抽屉与撤销流程（含单测）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 7: 批次列表页

**Files:**
- Modify: `haowugou-web/src/pages/imports/ImportBatchesPage.tsx`（替换占位）
- Test: `haowugou-web/src/pages/imports/ImportBatchesPage.test.tsx`

**Interfaces:**
- Consumes: Task 2 `useStoreSync`、Task 5 `listBatches`、Task 6 `BatchDetailDrawer`
- Produces: 完整批次列表页

- [ ] **Step 1: 写失败测试**

新建 `haowugou-web/src/pages/imports/ImportBatchesPage.test.tsx`：

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { UserProfile } from '../../api/types';
import { useAppStore } from '../../stores/app';
import { useAuthStore } from '../../stores/auth';
import ImportBatchesPage from './ImportBatchesPage';

vi.mock('../../api/imports', () => ({
  listBatches: vi.fn(),
  getBatch: vi.fn(),
}));

import * as importsApi from '../../api/imports';

const admin: UserProfile = {
  userId: 1, username: 'admin', displayName: '管理员', roleId: 1,
  role: 'ADMIN', store: null, canManage: true, canViewCostAndProfit: true,
};

const pageData = {
  store: { id: 5, storeCode: 'S005', storeName: '门店五' },
  items: [
    {
      batchId: 42, importType: 'DAILY_SALES', status: 'POSTED', dataDate: '2026-08-30',
      fileName: 'sales.xlsx', totalRows: 10, successRows: 10, errorRows: 0,
      importedAt: '2026-08-30T10:00:00', postedAt: '2026-08-30T10:00:01',
      reversedAt: null, reversible: true,
    },
    {
      batchId: 43, importType: 'INITIAL_INVENTORY', status: 'FAILED', dataDate: null,
      fileName: 'stock.xlsx', totalRows: 5, successRows: 0, errorRows: 5,
      importedAt: '2026-08-31T09:00:00', postedAt: null, reversedAt: null, reversible: false,
    },
  ],
  page: 0, size: 20, totalElements: 2, totalPages: 1,
};

function renderPage() {
  const router = createMemoryRouter(
    [{ path: '/stores/:storeId/import-batches', element: <ImportBatchesPage /> }],
    { initialEntries: ['/stores/5/import-batches'] },
  );
  render(<RouterProvider router={router} />);
}

describe('ImportBatchesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ profile: admin });
    useAppStore.setState({ stores: [], currentStoreId: 5 });
    vi.mocked(importsApi.listBatches).mockResolvedValue(pageData);
  });

  it('加载后渲染批次行与状态', async () => {
    renderPage();
    expect(await screen.findByText('sales.xlsx')).toBeInTheDocument();
    expect(screen.getByText('stock.xlsx')).toBeInTheDocument();
    expect(importsApi.listBatches).toHaveBeenCalledWith(5, { page: 0, size: 20 });
  });

  it('筛选提交后带筛选参数重新请求', async () => {
    renderPage();
    await screen.findByText('sales.xlsx');

    await userEvent.click(screen.getByLabelText('导入类型'));
    await userEvent.click(await screen.findByText('每日销售'));
    await userEvent.click(screen.getByRole('button', { name: /查\s*询/ }));

    await waitFor(() =>
      expect(importsApi.listBatches).toHaveBeenLastCalledWith(5, {
        importType: 'DAILY_SALES',
        page: 0,
        size: 20,
      }),
    );
  });

  it('行点击打开详情抽屉', async () => {
    vi.mocked(importsApi.getBatch).mockResolvedValue({
      store: pageData.store,
      batch: {
        batchId: 42, importType: 'DAILY_SALES', status: 'POSTED', dataDate: '2026-08-30',
        fileName: 'sales.xlsx', fileHash: 'a'.repeat(64), totalRows: 10, successRows: 10,
        errorRows: 0, errorMessage: null, operatorName: '管理员',
        importedAt: '2026-08-30T10:00:00', postedAt: '2026-08-30T10:00:01',
        reversedAt: null, reversedBy: null, reversedReason: null, reversible: true,
      },
      problemRows: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
    });
    renderPage();
    await userEvent.click(await screen.findByText('sales.xlsx'));

    expect(await screen.findByText('批次详情 #42')).toBeInTheDocument();
  });
});
```

注：antd Select 用 `getByLabelText` 需要 form item label 关联；若 Select 无 label 关联（inline 表单常用 Form.Item name+label），用 `screen.getByText('导入类型')` 定位标签后 `.closest('.ant-form-item')` 内找 combobox 或用 `document.querySelector('.ant-select-selector')` 单击。实现时保持「Form.Item label + Select」结构即可用 `getByLabelText`。

- [ ] **Step 2: 跑测试确认失败**

Expected: FAIL（占位组件无表格）。

- [ ] **Step 3: 实现**

改写 `haowugou-web/src/pages/imports/ImportBatchesPage.tsx`：

```tsx
import { Button, DatePicker, Form, Select, Space, Table, Tag } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  listBatches,
  type ImportBatchItem,
  type ImportBatchPage,
  type ImportBatchStatus,
  type ImportType,
} from '../../api/imports';
import { useStoreSync } from '../../hooks/useStoreSync';
import BatchDetailDrawer from './BatchDetailDrawer';

const STATUS_COLOR: Record<string, string> = {
  POSTED: 'green',
  REVERSED: 'default',
  FAILED: 'red',
  VALIDATING: 'blue',
  POSTING: 'gold',
};

const TYPE_LABEL: Record<string, string> = {
  INITIAL_INVENTORY: '初始库存',
  DAILY_SALES: '每日销售',
};

interface FilterValues {
  importType?: ImportType;
  status?: ImportBatchStatus;
  dateRange?: [Dayjs, Dayjs] | null;
}

export default function ImportBatchesPage() {
  const storeId = Number(useParams().storeId);
  useStoreSync(storeId);

  const [applied, setApplied] = useState<FilterValues>({});
  const [data, setData] = useState<ImportBatchPage | null>(null);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<ImportBatchItem | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setData(
        await listBatches(storeId, {
          importType: applied.importType,
          status: applied.status,
          dataDateFrom: applied.dateRange?.[0]?.format('YYYY-MM-DD'),
          dataDateTo: applied.dateRange?.[1]?.format('YYYY-MM-DD'),
          page,
          size: 20,
        }),
      );
    } finally {
      setLoading(false);
    }
  }, [storeId, applied, page]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <>
      <Form<FilterValues>
        layout="inline"
        style={{ marginBottom: 16 }}
        onFinish={(values) => {
          setApplied(values);
          setPage(0);
        }}
      >
        <Form.Item name="importType" label="导入类型">
          <Select
            allowClear
            style={{ width: 140 }}
            placeholder="全部"
            options={[
              { value: 'INITIAL_INVENTORY', label: '初始库存' },
              { value: 'DAILY_SALES', label: '每日销售' },
            ]}
          />
        </Form.Item>
        <Form.Item name="status" label="状态">
          <Select
            allowClear
            style={{ width: 140 }}
            placeholder="全部"
            options={['VALIDATING', 'POSTING', 'POSTED', 'REVERSED', 'FAILED'].map((s) => ({
              value: s,
              label: s,
            }))}
          />
        </Form.Item>
        <Form.Item name="dateRange" label="数据日期">
          <DatePicker.RangePicker />
        </Form.Item>
        <Form.Item>
          <Space>
            <Button type="primary" htmlType="submit">
              查 询
            </Button>
            <Button
              onClick={() => {
                setApplied({});
                setPage(0);
              }}
            >
              重 置
            </Button>
          </Space>
        </Form.Item>
      </Form>

      <Table<ImportBatchItem>
        rowKey="batchId"
        loading={loading}
        dataSource={data?.items ?? []}
        onRow={(record) => ({ onClick: () => setSelected(record), style: { cursor: 'pointer' } })}
        pagination={{
          current: (data?.page ?? 0) + 1,
          pageSize: data?.size ?? 20,
          total: data?.totalElements ?? 0,
          onChange: (p) => setPage(p - 1),
        }}
        columns={[
          { title: '批次ID', dataIndex: 'batchId', width: 90 },
          {
            title: '类型',
            dataIndex: 'importType',
            width: 110,
            render: (v: string) => <Tag>{TYPE_LABEL[v] ?? v}</Tag>,
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 120,
            render: (v: string) => <Tag color={STATUS_COLOR[v]}>{v}</Tag>,
          },
          { title: '数据日期', dataIndex: 'dataDate', width: 110, render: (v: string | null) => v ?? '—' },
          { title: '文件名', dataIndex: 'fileName', ellipsis: true },
          {
            title: '行数(成功/总)',
            width: 130,
            render: (_, r) =>
              r.errorRows > 0 ? (
                <span>
                  {r.successRows}/{r.totalRows} <span style={{ color: '#cf1322' }}>错误{r.errorRows}</span>
                </span>
              ) : (
                `${r.successRows}/${r.totalRows}`
              ),
          },
          { title: '上传时间', dataIndex: 'importedAt', width: 170 },
          {
            title: '撤销时间',
            dataIndex: 'reversedAt',
            width: 170,
            render: (v: string | null) => v ?? '—',
          },
          {
            title: '操作',
            width: 90,
            render: () => <Button type="link" size="small">详情</Button>,
          },
        ]}
      />

      <BatchDetailDrawer
        storeId={storeId}
        batchId={selected?.batchId ?? null}
        open={selected != null}
        onClose={() => setSelected(null)}
        onReversed={() => {
          setSelected(null);
          void load();
        }}
      />
    </>
  );
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd haowugou-web && npm run test && npm run build
```

Expected: PASS + 构建无报错。

- [ ] **Step 5: 提交**

```bash
git add haowugou-web/src/pages/imports/ImportBatchesPage.tsx haowugou-web/src/pages/imports/ImportBatchesPage.test.tsx
git commit -m "feat: 批次列表页（筛选/分页/详情入口，含单测）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 8: 结果面板与初始库存导入页

**Files:**
- Create: `haowugou-web/src/pages/imports/ImportResultPanel.tsx`
- Modify: `haowugou-web/src/pages/imports/InventoryImportPage.tsx`（替换占位）
- Test: `haowugou-web/src/pages/imports/InventoryImportPage.test.tsx`

**Interfaces:**
- Consumes: Task 5 `importInventory`、`listWarehouses`、类型；Task 2 `useStoreSync`
- Produces: `ImportResultPanel` props `{ result: ImportResult; onViewBatch: () => void }`——Task 9 复用；`InventoryImportPage` 完整上传页

- [ ] **Step 1: 写失败测试**

新建 `haowugou-web/src/pages/imports/InventoryImportPage.test.tsx`：

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { UserProfile } from '../../api/types';
import { useAuthStore } from '../../stores/auth';
import InventoryImportPage from './InventoryImportPage';

vi.mock('../../api/imports', () => ({
  importInventory: vi.fn(),
  listWarehouses: vi.fn().mockResolvedValue([]),
}));

vi.mock('../../api/http', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/http')>();
  return { ...actual, getProblemDetailMessage: vi.fn() };
});

import * as importsApi from '../../api/imports';
import { getProblemDetailMessage } from '../../api/http';

const admin: UserProfile = {
  userId: 1, username: 'admin', displayName: '管理员', roleId: 1,
  role: 'ADMIN', store: null, canManage: true, canViewCostAndProfit: true,
};

const postedResult = {
  batchId: 9, status: 'POSTED', totalRows: 3, successRows: 3, errorRows: 0, errors: [],
};
const failedResult = {
  batchId: 10, status: 'FAILED', totalRows: 3, successRows: 0, errorRows: 2,
  errors: [
    { rowNumber: 2, barcode: 'B1', message: '未知条码' },
    { rowNumber: 3, barcode: null, message: '数量为负' },
  ],
};

function renderPage() {
  const router = createMemoryRouter(
    [
      { path: '/stores/:storeId/imports/inventory', element: <InventoryImportPage /> },
      { path: '/stores/:storeId/import-batches', element: <div>批次列表页</div> },
    ],
    { initialEntries: ['/stores/5/imports/inventory'] },
  );
  const { container } = render(<RouterProvider router={router} />);
  return container;
}

async function pickFile(container: HTMLElement) {
  const input = container.querySelector('input[type="file"]') as HTMLInputElement;
  const file = new File(['x'], 'stock.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
  await userEvent.upload(input, file);
}

describe('InventoryImportPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ profile: admin });
    vi.mocked(importsApi.listWarehouses).mockResolvedValue([]);
  });

  it('选择文件后提交，构建正确 FormData', async () => {
    vi.mocked(importsApi.importInventory).mockResolvedValue(postedResult);
    const container = renderPage();
    await pickFile(container);
    await userEvent.click(screen.getByRole('button', { name: /开始导入/ }));

    await waitFor(() => expect(importsApi.importInventory).toHaveBeenCalled());
    const [storeId, file, warehouseId] = importsApi.importInventory.mock.calls[0];
    expect(storeId).toBe(5);
    expect(file).toBeInstanceOf(File);
    expect(warehouseId).toBeUndefined();
  });

  it('POSTED 渲染成功面板与查看批次入口', async () => {
    vi.mocked(importsApi.importInventory).mockResolvedValue(postedResult);
    const container = renderPage();
    await pickFile(container);
    await userEvent.click(screen.getByRole('button', { name: /开始导入/ }));

    expect(await screen.findByText('导入成功')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /查看批次/ }));
    expect(await screen.findByText('批次列表页')).toBeInTheDocument();
  });

  it('FAILED 渲染行级错误表', async () => {
    vi.mocked(importsApi.importInventory).mockResolvedValue(failedResult);
    const container = renderPage();
    await pickFile(container);
    await userEvent.click(screen.getByRole('button', { name: /开始导入/ }));

    expect(await screen.findByText('整批未入账')).toBeInTheDocument();
    expect(screen.getByText('未知条码')).toBeInTheDocument();
    expect(screen.getByText('数量为负')).toBeInTheDocument();
  });

  it('409 展示后端文案且保留表单', async () => {
    vi.mocked(importsApi.importInventory).mockRejectedValue(new Error('409'));
    vi.mocked(getProblemDetailMessage).mockReturnValue('已有有效初始库存批次，先撤销再导入');
    const container = renderPage();
    await pickFile(container);
    await userEvent.click(screen.getByRole('button', { name: /开始导入/ }));

    expect(await screen.findByText('已有有效初始库存批次，先撤销再导入')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Expected: FAIL（占位组件无上传）。

- [ ] **Step 3: 实现 ImportResultPanel**

新建 `haowugou-web/src/pages/imports/ImportResultPanel.tsx`：

```tsx
import { Alert, Button, Descriptions, Result, Table } from 'antd';
import type { ImportResult } from '../../api/imports';

interface Props {
  result: ImportResult;
  /** 展示额外统计项（每日销售导入）。 */
  extraItems?: { label: string; value: React.ReactNode }[];
  onViewBatch: () => void;
}

export default function ImportResultPanel({ result, extraItems, onViewBatch }: Props) {
  if (result.status === 'POSTED') {
    return (
      <Result
        status="success"
        title="导入成功"
        subTitle={`批次 #${result.batchId} 已入账`}
        extra={<Button type="primary" onClick={onViewBatch}>查看批次</Button>}
      >
        <Descriptions column={3} size="small">
          <Descriptions.Item label="原始行数">{result.totalRows}</Descriptions.Item>
          <Descriptions.Item label="成功行数">{result.successRows}</Descriptions.Item>
          {extraItems?.map((item) => (
            <Descriptions.Item key={item.label} label={item.label}>{item.value}</Descriptions.Item>
          ))}
        </Descriptions>
      </Result>
    );
  }
  return (
    <>
      <Alert
        type="error"
        showIcon
        message="整批未入账"
        description={`${result.errorRows} 行存在错误，全有或全无，请修正文件后重新上传。`}
        style={{ marginBottom: 16 }}
      />
      <Table
        rowKey="rowNumber"
        size="small"
        dataSource={result.errors}
        pagination={false}
        columns={[
          { title: 'Excel 行号', dataIndex: 'rowNumber', width: 110 },
          { title: '条码', dataIndex: 'barcode', render: (v: string | null) => v ?? '—' },
          { title: '错误原因', dataIndex: 'message' },
        ]}
      />
      {result.errorRows > result.errors.length && (
        <p style={{ marginTop: 8, color: '#8c8c8c' }}>仅显示前 {result.errors.length} 条，共 {result.errorRows} 条</p>
      )}
    </>
  );
}
```

- [ ] **Step 4: 实现 InventoryImportPage**

改写 `haowugou-web/src/pages/imports/InventoryImportPage.tsx`：

```tsx
import { InboxOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Form, Select, Typography, Upload, type UploadFile } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  importInventory,
  listWarehouses,
  type ImportResult,
  type WarehouseView,
} from '../../api/imports';
import { getProblemDetailMessage } from '../../api/http';
import { useStoreSync } from '../../hooks/useStoreSync';
import ImportResultPanel from './ImportResultPanel';

interface FormValues {
  file?: UploadFile[];
  warehouseId?: number;
}

/** antd Upload 与 Form 的标准接线：表单态存 fileList，提交时取 originFileObj。 */
function normFile(e: { fileList: UploadFile[] }): UploadFile[] {
  return e?.fileList ?? [];
}

export default function InventoryImportPage() {
  const storeId = Number(useParams().storeId);
  useStoreSync(storeId);
  const navigate = useNavigate();

  const [form] = Form.useForm<FormValues>();
  const [warehouses, setWarehouses] = useState<WarehouseView[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);

  useEffect(() => {
    void listWarehouses(storeId).then(setWarehouses).catch(() => setWarehouses([]));
  }, [storeId]);

  const onFinish = async (values: FormValues) => {
    const file = values.file?.[0]?.originFileObj as File | undefined;
    if (!file) {
      return;
    }
    setSubmitting(true);
    setSubmitError(null);
    setResult(null);
    try {
      setResult(await importInventory(storeId, file, values.warehouseId));
      form.resetFields();
    } catch (e) {
      setSubmitError(getProblemDetailMessage(e) ?? '导入失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card title="初始库存导入">
      {submitError && <Alert type="error" title={submitError} showIcon style={{ marginBottom: 16 }} />}
      {result ? (
        <ImportResultPanel
          result={result}
          onViewBatch={() => navigate(`/stores/${storeId}/import-batches`)}
        />
      ) : (
        <Form<FormValues> form={form} layout="vertical" onFinish={onFinish}>
          <Form.Item
            name="file"
            valuePropName="fileList"
            getValueFromEvent={normFile}
            label="POS 商品资料工作簿（.xls / .xlsx）"
          >
            <Upload.Dragger
              maxCount={1}
              beforeUpload={(file) => {
                const ok = /\.(xlsx?|xls)$/i.test(file.name);
                if (!ok) {
                  form.setFields([{ name: 'file', errors: ['仅支持 .xls / .xlsx 文件'] }]);
                  return Upload.LIST_IGNORE;
                }
                return false; // 由表单接管，提交时统一上传
              }}
            >
              <p className="ant-upload-drag-icon"><InboxOutlined /></p>
              <p className="ant-upload-text">点击或拖拽文件到此区域</p>
            </Upload.Dragger>
          </Form.Item>
          <Form.Item name="warehouseId" label="入库仓库（可选，不选则仓库待分配）">
            <Select
              allowClear
              style={{ width: 320 }}
              placeholder="不指定"
              options={warehouses.map((w) => ({ value: w.id, label: `${w.warehouseName}（${w.warehouseCode ?? '-'}）` }))}
            />
          </Form.Item>
          <Typography.Text type="secondary">注意：遇到未知条码将导致整批失败（全有或全无）。</Typography.Text>
          <Form.Item noStyle shouldUpdate>
            {() => (
              <Button
                type="primary"
                htmlType="submit"
                loading={submitting}
                disabled={!form.getFieldValue('file')?.length}
                style={{ marginTop: 16 }}
              >
                开始导入
              </Button>
            )}
          </Form.Item>
        </Form>
      )}
    </Card>
  );
}
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd haowugou-web && npm run test && npm run build
```

Expected: PASS（4 条用例）+ 构建无报错。

- [ ] **Step 6: 提交**

```bash
git add haowugou-web/src/pages/imports/ImportResultPanel.tsx haowugou-web/src/pages/imports/InventoryImportPage.tsx haowugou-web/src/pages/imports/InventoryImportPage.test.tsx
git commit -m "feat: 初始库存导入页与结果面板（含单测）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 9: 每日销售导入页

**Files:**
- Modify: `haowugou-web/src/pages/imports/SalesImportPage.tsx`（替换占位）
- Test: `haowugou-web/src/pages/imports/SalesImportPage.test.tsx`

**Interfaces:**
- Consumes: Task 5 `importDailySales`、Task 8 `ImportResultPanel`
- Produces: 完整每日销售导入页

- [ ] **Step 1: 写失败测试**

新建 `haowugou-web/src/pages/imports/SalesImportPage.test.tsx`：

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { UserProfile } from '../../api/types';
import { useAuthStore } from '../../stores/auth';
import SalesImportPage from './SalesImportPage';

vi.mock('../../api/imports', () => ({
  importDailySales: vi.fn(),
}));

import * as importsApi from '../../api/imports';

const admin: UserProfile = {
  userId: 1, username: 'admin', displayName: '管理员', roleId: 1,
  role: 'ADMIN', store: null, canManage: true, canViewCostAndProfit: true,
};

const postedResult = {
  batchId: 11, status: 'POSTED', totalRows: 8, successRows: 8, errorRows: 0,
  salesRows: 7, pendingProductsCreated: 1, deductedProducts: 6, errors: [],
};

function renderPage() {
  const router = createMemoryRouter(
    [
      { path: '/stores/:storeId/imports/sales', element: <SalesImportPage /> },
      { path: '/stores/:storeId/import-batches', element: <div>批次列表页</div> },
    ],
    { initialEntries: ['/stores/5/imports/sales'] },
  );
  const { container } = render(<RouterProvider router={router} />);
  return container;
}

async function pickFile(container: HTMLElement) {
  const input = container.querySelector('input[type="file"]') as HTMLInputElement;
  await userEvent.upload(input, new File(['x'], 'sales.xlsx'));
}

describe('SalesImportPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ profile: admin });
  });

  it('业务日期必填', async () => {
    const container = renderPage();
    await pickFile(container);
    await userEvent.click(screen.getByRole('button', { name: /开始导入/ }));
    expect(importsApi.importDailySales).not.toHaveBeenCalled();
    expect(await screen.findByText('请选择业务日期')).toBeInTheDocument();
  });

  it('选择文件与日期后提交，构建正确 FormData', async () => {
    vi.mocked(importsApi.importDailySales).mockResolvedValue(postedResult);
    const container = renderPage();
    await pickFile(container);
    await userEvent.click(screen.getByPlaceholderText('请选择业务日期'));
    await userEvent.click(await screen.findByTitle('2026-09-01'));
    await userEvent.click(screen.getByRole('button', { name: /开始导入/ }));

    await waitFor(() => expect(importsApi.importDailySales).toHaveBeenCalled());
    const [storeId, file, businessDate] = importsApi.importDailySales.mock.calls[0];
    expect(storeId).toBe(5);
    expect(file).toBeInstanceOf(File);
    expect(businessDate).toBe('2026-09-01');
  });

  it('POSTED 渲染成功面板与销售统计项', async () => {
    vi.mocked(importsApi.importDailySales).mockResolvedValue(postedResult);
    const container = renderPage();
    await pickFile(container);
    await userEvent.click(screen.getByPlaceholderText('请选择业务日期'));
    await userEvent.click(await screen.findByTitle('2026-09-01'));
    await userEvent.click(screen.getByRole('button', { name: /开始导入/ }));

    expect(await screen.findByText('导入成功')).toBeInTheDocument();
    expect(screen.getByText('7')).toBeInTheDocument(); // salesRows
  });
});
```

注：DatePicker 在 jsdom 里用 `findByTitle` 选择日期单元格需要面板已展开；若 antd v6 面板渲染结构不同，改为直接 `form.setFieldsValue({ businessDate: dayjs('2026-09-01') })` 前先 `render`，通过 `document.querySelector('.ant-picker-input input')` 输入文本 `2026-09-01` 再按回车——实现时以能稳定驱动为准，断言不变。

- [ ] **Step 2: 跑测试确认失败**

Expected: FAIL（占位组件无表单）。

- [ ] **Step 3: 实现**

改写 `haowugou-web/src/pages/imports/SalesImportPage.tsx`：

```tsx
import { InboxOutlined } from '@ant-design/icons';
import { Alert, Button, Card, DatePicker, Form, Typography, Upload, type UploadFile } from 'antd';
import type { Dayjs } from 'dayjs';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { importDailySales, type ImportResult } from '../../api/imports';
import { getProblemDetailMessage } from '../../api/http';
import { useStoreSync } from '../../hooks/useStoreSync';
import ImportResultPanel from './ImportResultPanel';

interface FormValues {
  file?: UploadFile[];
  businessDate: Dayjs;
}

/** antd Upload 与 Form 的标准接线：表单态存 fileList，提交时取 originFileObj。 */
function normFile(e: { fileList: UploadFile[] }): UploadFile[] {
  return e?.fileList ?? [];
}

export default function SalesImportPage() {
  const storeId = Number(useParams().storeId);
  useStoreSync(storeId);
  const navigate = useNavigate();

  const [form] = Form.useForm<FormValues>();
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);

  const onFinish = async (values: FormValues) => {
    const file = values.file?.[0]?.originFileObj as File | undefined;
    if (!file) {
      return;
    }
    setSubmitting(true);
    setSubmitError(null);
    setResult(null);
    try {
      setResult(
        await importDailySales(storeId, file, values.businessDate.format('YYYY-MM-DD')),
      );
      form.resetFields();
    } catch (e) {
      setSubmitError(getProblemDetailMessage(e) ?? '导入失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card title="每日销售导入">
      {submitError && <Alert type="error" title={submitError} showIcon style={{ marginBottom: 16 }} />}
      {result ? (
        <ImportResultPanel
          result={result}
          extraItems={[
            { label: '销售事实条数', value: result.salesRows ?? '—' },
            { label: '新建待完善商品', value: result.pendingProductsCreated ?? '—' },
            { label: '扣库存商品数', value: result.deductedProducts ?? '—' },
          ]}
          onViewBatch={() => navigate(`/stores/${storeId}/import-batches`)}
        />
      ) : (
        <Form<FormValues> form={form} layout="vertical" onFinish={onFinish}>
          <Form.Item
            name="file"
            valuePropName="fileList"
            getValueFromEvent={normFile}
            label="POS 商品销售汇总工作簿（.xls / .xlsx）"
          >
            <Upload.Dragger
              maxCount={1}
              beforeUpload={(file) => {
                const ok = /\.(xlsx?|xls)$/i.test(file.name);
                if (!ok) {
                  form.setFields([{ name: 'file', errors: ['仅支持 .xls / .xlsx 文件'] }]);
                  return Upload.LIST_IGNORE;
                }
                return false; // 由表单接管，提交时统一上传
              }}
            >
              <p className="ant-upload-drag-icon"><InboxOutlined /></p>
              <p className="ant-upload-text">点击或拖拽文件到此区域</p>
            </Upload.Dragger>
          </Form.Item>
          <Form.Item
            name="businessDate"
            label="业务日期"
            rules={[{ required: true, message: '请选择业务日期' }]}
          >
            <DatePicker style={{ width: 200 }} placeholder="请选择业务日期" />
          </Form.Item>
          <Typography.Text type="secondary">
            注意：未识别供应商将按归并口径入账；未知条码自动新建「待完善」商品，销售照常入账。
          </Typography.Text>
          <Form.Item noStyle shouldUpdate>
            {() => (
              <Button
                type="primary"
                htmlType="submit"
                loading={submitting}
                disabled={!form.getFieldValue('file')?.length}
                style={{ marginTop: 16 }}
              >
                开始导入
              </Button>
            )}
          </Form.Item>
        </Form>
      )}
    </Card>
  );
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd haowugou-web && npm run test && npm run build
```

Expected: PASS（3 条用例）+ 构建无报错。

- [ ] **Step 5: 提交**

```bash
git add haowugou-web/src/pages/imports/SalesImportPage.tsx haowugou-web/src/pages/imports/SalesImportPage.test.tsx
git commit -m "feat: 每日销售导入页（含单测）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 10: 全量验证、手动闭环与收尾

**Files:**
- Modify: `docs/design/2026-09-02-frontend-import-slice-design.md`（状态行）
- 无代码文件（如需修复见各步骤说明）

**Interfaces:**
- Consumes: Task 1–9 全部产物
- Produces: 全绿测试 + 构建 + 手动闭环记录 + 推送

- [ ] **Step 1: 全量测试与构建**

```bash
cd haowugou-web && npm run test && npm run build
```

Expected: 全量 PASS（既有 29 条 + 新增约 35 条）+ 构建无 TS 报错。

- [ ] **Step 2: 手动闭环（后端 + MySQL 需在跑）**

先起后端（MySQL80 服务已在跑，jar 用 `mvn -pl haowugou-bootstrap -am package -DskipTests` 构建或复用 target 下已有产物）：

```bash
cd D:/Dev/Code/Project/Intelligent-Sales-Management-System-main
java -jar haowugou-bootstrap/target/haowugou-bootstrap-0.0.1-SNAPSHOT.jar
```

再起前端：

```bash
cd haowugou-web && npm run dev
```

**库数据前提**：本地库 `store`/`warehouse` 表无种子数据（schema.sql 无 INSERT）。若为空，先手工种入门店与仓库（密码从 gitignored 的 `application-local.yml` 取，不回显）：

```bash
mysql -h127.0.0.1 -uroot -p"$PWD" --default-character-set=utf8mb4 haowugou <<'SQL'
INSERT INTO store (store_code, store_name, is_active) VALUES ('IT-001', '集成测试门店', 1);
INSERT INTO warehouse (store_id, warehouse_code, warehouse_name)
SELECT id, 'W001', '主仓' FROM store WHERE store_code = 'IT-001';
SQL
```

其中 `$PWD` 需先执行 `PWD=$(grep -E "^\s+password:" haowugou-bootstrap/src/main/resources/application-local.yml | head -1 | sed 's/.*password:\s*//')`。种子后重跑 `database/migration/2026-09-01-app-user.sql` 可顺带补出 `store1user`（可选，导入页只需 admin）。

浏览器走（admin / Admin@123）：

1. 登录 → 顶栏选择器出现「未选门店」→ 选门店 → 侧边菜单启用；
2. 每日销售导入：用真实 POS 文件（桌面，环境变量 `HAOWUGOU_POS_SALES_FILE` 指向的文件）上传 → 成功面板 → 「查看批次」；
3. 批次列表：筛选（类型/状态/日期）→ 行点击 → 详情抽屉（元信息/问题行分页）→ 撤销（填原因）→ 成功提示含「同一文件可重新上传」→ 列表状态变 REVERSED；
4. 重传同一文件同日期 → 成功（撤销释放坑位生效）；
5. 初始库存导入：造一个含未知条码的小 xlsx → FAILED → 错误表显示行号与条码；
6. 重复上传同一文件 → 409 文案展示。

Expected: 6 步全部符合预期。若某步失败，按 systematic-debugging 定位（优先看 Network 的响应体与请求头 `X-XSRF-TOKEN`）。

- [ ] **Step 3: 更新设计文档状态并提交推送**

`docs/design/2026-09-02-frontend-import-slice-design.md` 首行「- 状态：已设计（待实现）」改为「- 状态：已实现 —— 10 个任务完成，单测 N 条 + 真实后端手动闭环通过」。

```bash
git add docs/design/2026-09-02-frontend-import-slice-design.md
git commit -m "docs: 前端导入切片状态更新为已实现

Co-Authored-By: Claude <noreply@anthropic.com>"
git push
```

- [ ] **Step 4: 汇报**

汇总提交列表、测试数量、手动闭环结果与遗留事项（如初始库存无真实文件仅用自造小文件验证）。
