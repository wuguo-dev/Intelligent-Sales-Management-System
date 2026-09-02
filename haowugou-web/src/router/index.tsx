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
