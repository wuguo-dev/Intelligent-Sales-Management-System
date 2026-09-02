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
