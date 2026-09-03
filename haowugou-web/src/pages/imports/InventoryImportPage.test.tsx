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
import type { ImportResult } from '../../api/imports';
import { getProblemDetailMessage } from '../../api/http';

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

const postedResult: ImportResult = {
  batchId: '9',
  status: 'POSTED',
  totalRows: 3,
  successRows: 3,
  errorRows: 0,
  errors: [],
};
const failedResult: ImportResult = {
  batchId: '10',
  status: 'FAILED',
  totalRows: 3,
  successRows: 0,
  errorRows: 2,
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
  const file = new File(['x'], 'stock.xlsx');
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
    const [storeId, file, warehouseId] = vi.mocked(importsApi.importInventory).mock.calls[0];
    expect(storeId).toBe('5');
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

  it('409 展示后端文案', async () => {
    vi.mocked(importsApi.importInventory).mockRejectedValue(new Error('409'));
    vi.mocked(getProblemDetailMessage).mockReturnValue('已有有效初始库存批次，先撤销再导入');
    const container = renderPage();
    await pickFile(container);
    await userEvent.click(screen.getByRole('button', { name: /开始导入/ }));

    expect(await screen.findByText('已有有效初始库存批次，先撤销再导入')).toBeInTheDocument();
  });
});
