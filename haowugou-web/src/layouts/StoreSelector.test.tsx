import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider, useParams } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { StoreView, UserProfile } from '../api/types';
import { useAppStore } from '../stores/app';
import { useAuthStore } from '../stores/auth';
import StoreSelector from './StoreSelector';

vi.mock('../api/stores', () => ({ listStores: vi.fn() }));

import * as storesApi from '../api/stores';

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
const stores: StoreView[] = [
  { id: '1', storeCode: 'S001', storeName: '门店一' },
  { id: '2', storeCode: 'S002', storeName: '门店二' },
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
    // 挂载时 loadStores 会调这个 mock，需返回固定门店列表避免覆盖测试种入的数据
    vi.mocked(storesApi.listStores).mockResolvedValue(stores);
    useAuthStore.setState({ profile: admin });
    useAppStore.setState({ stores, currentStoreId: '1' });
  });

  it('管理员看到选择器并展示当前门店', () => {
    renderSelector();
    expect(screen.getByText('门店一')).toBeInTheDocument();
  });

  it('切换门店后选择状态更新并导航到新门店路径', async () => {
    renderSelector();
    await userEvent.click(screen.getByText('门店一'));
    await userEvent.click(await screen.findByText('门店二'));
    expect(useAppStore.getState().currentStoreId).toBe('2');
    expect(await screen.findByText('storeId=2')).toBeInTheDocument();
  });

  it('普通用户不渲染选择器', () => {
    useAuthStore.setState({
      profile: {
        userId: '2',
        username: 'store1user',
        displayName: '门店查询员',
        roleId: 2,
        role: 'USER',
        store: { id: '1', storeCode: 'S001', storeName: '门店一' },
        canManage: false,
        canViewCostAndProfit: false,
      },
    });
    // useNavigate 需要 Router 上下文，用内存路由包裹
    const router = createMemoryRouter(
      [{ path: '/', element: <StoreSelector /> }],
      { initialEntries: ['/'] },
    );
    const { container } = render(<RouterProvider router={router} />);
    expect(container).toBeEmptyDOMElement();
  });
});
