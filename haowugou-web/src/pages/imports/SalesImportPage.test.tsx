import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import dayjs, { type Dayjs } from 'dayjs';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ImportResult } from '../../api/imports';
import type { UserProfile } from '../../api/types';
import { useAuthStore } from '../../stores/auth';
import SalesImportPage from './SalesImportPage';

vi.mock('../../api/imports', () => ({
  importDailySales: vi.fn(),
}));

import * as importsApi from '../../api/imports';

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

const postedResult: ImportResult = {
  batchId: 11,
  status: 'POSTED',
  totalRows: 8,
  successRows: 8,
  errorRows: 0,
  salesRows: 7,
  pendingProductsCreated: 1,
  deductedProducts: 6,
  errors: [],
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

/**
 * 点开日期面板后选「当月 3 日」并返回所选日期。
 * 用当前月份动态计算目标日期（面板默认展示当月）；回车驱动会触发表单隐式提交，不可用。
 */
async function pickDate(container: HTMLElement): Promise<Dayjs> {
  const input = container.querySelector('.ant-picker-input input') as HTMLInputElement;
  await userEvent.click(input);
  const target = dayjs().startOf('month').add(2, 'day');
  await userEvent.click(await screen.findByTitle(target.format('YYYY-MM-DD')));
  return target;
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
    const picked = await pickDate(container);
    await userEvent.click(screen.getByRole('button', { name: /开始导入/ }));

    await waitFor(() => expect(importsApi.importDailySales).toHaveBeenCalled());
    const [storeId, file, businessDate] = vi.mocked(importsApi.importDailySales).mock.calls[0];
    expect(storeId).toBe(5);
    expect(file).toBeInstanceOf(File);
    expect(businessDate).toBe(picked.format('YYYY-MM-DD'));
  });

  it('POSTED 渲染成功面板与销售统计项', async () => {
    vi.mocked(importsApi.importDailySales).mockResolvedValue(postedResult);
    const container = renderPage();
    await pickFile(container);
    await pickDate(container);
    await userEvent.click(screen.getByRole('button', { name: /开始导入/ }));

    expect(await screen.findByText('导入成功')).toBeInTheDocument();
    expect(screen.getByText('销售事实条数')).toBeInTheDocument();
    expect(screen.getByText('新建待完善商品')).toBeInTheDocument();
  });
});
