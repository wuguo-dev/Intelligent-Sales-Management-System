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

function renderLayout() {
  const router = createMemoryRouter(
    [
      {
        path: '/',
        element: <MainLayout />,
        children: [{ index: true, element: <div>内容区</div> }],
      },
      { path: '/login', element: <div>登录页</div> },
    ],
    { initialEntries: ['/'] },
  );
  render(<RouterProvider router={router} />);
  return router;
}

describe('MainLayout', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ profile: user });
  });

  it('顶栏展示用户名与门店', () => {
    renderLayout();
    expect(screen.getByText('门店一用户')).toBeInTheDocument();
    expect(screen.getByText('好物购')).toBeInTheDocument();
    expect(screen.getByText('内容区')).toBeInTheDocument();
  });

  it('点击退出登录调用 logout 并回登录页', async () => {
    renderLayout();

    await userEvent.click(screen.getByText('门店一用户'));
    await userEvent.click(await screen.findByText('退出登录'));

    expect(authApi.logout).toHaveBeenCalled();
    expect(useAuthStore.getState().profile).toBeNull();
    expect(await screen.findByText('登录页')).toBeInTheDocument();
  });
});
