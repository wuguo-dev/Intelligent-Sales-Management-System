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
  userId: '1',
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
