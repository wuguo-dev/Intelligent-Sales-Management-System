import { render, screen, waitFor, within } from '@testing-library/react';
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
import type { ImportBatchPage } from '../../api/imports';

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

const pageData: ImportBatchPage = {
  store: { id: '5', storeCode: 'S005', storeName: '门店五' },
  items: [
    {
      batchId: '42',
      importType: 'DAILY_SALES',
      status: 'POSTED',
      dataDate: '2026-08-30',
      fileName: 'sales.xlsx',
      totalRows: 10,
      successRows: 10,
      errorRows: 0,
      importedAt: '2026-08-30T10:00:00',
      postedAt: '2026-08-30T10:00:01',
      reversedAt: null,
      reversible: true,
    },
    {
      batchId: '43',
      importType: 'INITIAL_INVENTORY',
      status: 'FAILED',
      dataDate: null,
      fileName: 'stock.xlsx',
      totalRows: 5,
      successRows: 0,
      errorRows: 5,
      importedAt: '2026-08-31T09:00:00',
      postedAt: null,
      reversedAt: null,
      reversible: false,
    },
  ],
  page: 0,
  size: 20,
  totalElements: 2,
  totalPages: 1,
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
    useAppStore.setState({ stores: [], currentStoreId: '5' });
    vi.mocked(importsApi.listBatches).mockResolvedValue(pageData);
  });

  it('加载后渲染批次行与状态', async () => {
    renderPage();
    expect(await screen.findByText('sales.xlsx')).toBeInTheDocument();
    expect(screen.getByText('stock.xlsx')).toBeInTheDocument();
    expect(importsApi.listBatches).toHaveBeenCalledWith('5', { page: 0, size: 20 });
  });

  it('筛选提交后带筛选参数重新请求', async () => {
    renderPage();
    await screen.findByText('sales.xlsx');

    await userEvent.click(screen.getByLabelText('导入类型'));
    // 可见下拉容器（antd v6 的无障碍 listbox 是隐藏镜像，不能用 findByRole 定位）
    const dropdown = await waitFor(() => {
      const el = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
      expect(el).not.toBeNull();
      return el as HTMLElement;
    });
    await userEvent.click(within(dropdown).getByText('每日销售'));
    await userEvent.click(screen.getByRole('button', { name: /查\s*询/ }));

    await waitFor(() =>
      expect(importsApi.listBatches).toHaveBeenLastCalledWith('5', {
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
        batchId: '42',
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
    });
    renderPage();
    await userEvent.click(await screen.findByText('sales.xlsx'));

    expect(await screen.findByText('批次详情 #42')).toBeInTheDocument();
  });
});
