import { renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { UserProfile } from '../api/types';
import { useAppStore } from '../stores/app';
import { useAuthStore } from '../stores/auth';
import { useStoreSync } from './useStoreSync';

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
const user: UserProfile = {
  userId: 2,
  username: 'store1user',
  displayName: '门店查询员',
  roleId: 2,
  role: 'USER',
  store: { id: 7, storeCode: 'S007', storeName: '门店七' },
  canManage: false,
  canViewCostAndProfit: false,
};

describe('useStoreSync', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ profile: admin });
    useAppStore.setState({ currentStoreId: null, stores: [] });
  });

  it('管理员进入业务页时把 URL 门店同步进 app store', () => {
    const { rerender } = renderHook(({ id }: { id: number }) => useStoreSync(id), {
      initialProps: { id: 5 },
    });
    expect(useAppStore.getState().currentStoreId).toBe(5);
    rerender({ id: 6 });
    expect(useAppStore.getState().currentStoreId).toBe(6);
  });

  it('普通用户不动 app store（门店来自 profile）', () => {
    useAuthStore.setState({ profile: user });
    renderHook(() => useStoreSync(3));
    expect(useAppStore.getState().currentStoreId).toBeNull();
  });
});
