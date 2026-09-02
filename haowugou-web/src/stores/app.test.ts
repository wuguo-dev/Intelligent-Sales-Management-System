import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { StoreView } from '../api/types';
import { useAppStore } from './app';

vi.mock('../api/stores', () => ({ listStores: vi.fn() }));

import * as storesApi from '../api/stores';

const stores: StoreView[] = [
  { id: 1, storeCode: 'S001', storeName: '门店一' },
  { id: 2, storeCode: 'S002', storeName: '门店二' },
];

describe('app store', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAppStore.setState({ stores: [], currentStoreId: null });
  });

  it('loadStores 成功后保存门店列表', async () => {
    vi.mocked(storesApi.listStores).mockResolvedValue(stores);
    await useAppStore.getState().loadStores();
    expect(useAppStore.getState().stores).toEqual(stores);
  });

  it('loadStores 失败时静默置空不抛错', async () => {
    vi.mocked(storesApi.listStores).mockRejectedValue(new Error('403'));
    await expect(useAppStore.getState().loadStores()).resolves.toBeUndefined();
    expect(useAppStore.getState().stores).toEqual([]);
  });

  it('selectStore 保存选中门店', () => {
    useAppStore.getState().selectStore(2);
    expect(useAppStore.getState().currentStoreId).toBe(2);
  });

  it('clearStore 清空选中与列表', () => {
    useAppStore.setState({ stores, currentStoreId: 1 });
    useAppStore.getState().clearStore();
    expect(useAppStore.getState().currentStoreId).toBeNull();
    expect(useAppStore.getState().stores).toEqual([]);
  });
});
