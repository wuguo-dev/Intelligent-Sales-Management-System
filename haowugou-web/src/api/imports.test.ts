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
    expect(http.post).toHaveBeenCalledWith('/api/stores/5/import-batches/42/reverse', {
      reversedBy: '管理员',
      reversedReason: '数据日期填错',
    });
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
