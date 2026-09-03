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
import type { ImportBatchDetail } from '../../api/imports';
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

const detail: ImportBatchDetail = {
  store: { id: '5', storeCode: 'S005', storeName: '门店五' },
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
};

function renderDrawer(batch: typeof detail = detail) {
  vi.mocked(importsApi.getBatch).mockResolvedValue(batch);
  const onClose = vi.fn();
  const onReversed = vi.fn();
  render(
    <BatchDetailDrawer
      storeId={'5'}
      batchId={'42'}
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
    expect(importsApi.getBatch).toHaveBeenCalledWith('5', '42', 0, 20);
  });

  it('可撤销批次点击撤销：默认操作人为当前用户、原因必填', async () => {
    vi.mocked(importsApi.reverseBatch).mockResolvedValue({
      store: { id: '5', storeCode: 'S005', storeName: '门店五' },
      batchId: '42',
      importType: 'DAILY_SALES',
      dataDate: '2026-08-30',
      fileName: 'sales.xlsx',
      reversedMovements: 10,
      restoredProducts: 8,
      reversedAt: '2026-09-01T09:00:00',
      reversedBy: '管理员',
      reversedReason: '填错日期',
    });
    const { onReversed } = renderDrawer();

    await userEvent.click(await screen.findByRole('button', { name: /撤销批次/ }));
    // 弹窗内默认操作人
    const operatorInput = await screen.findByDisplayValue('管理员');
    expect(operatorInput).toBeInTheDocument();

    await userEvent.type(screen.getByPlaceholderText('撤销原因'), '填错日期');
    await userEvent.click(screen.getByRole('button', { name: /确认撤销/ }));

    await waitFor(() => expect(importsApi.reverseBatch).toHaveBeenCalled());
    expect(importsApi.reverseBatch).toHaveBeenCalledWith('5', '42', {
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
