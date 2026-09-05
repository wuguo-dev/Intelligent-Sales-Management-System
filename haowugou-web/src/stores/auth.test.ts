import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { UserProfile } from '../api/types';
import { useAuthStore } from './auth';

vi.mock('../api/auth', () => ({
  login: vi.fn(),
  fetchMe: vi.fn(),
  logout: vi.fn(),
}));

import * as authApi from '../api/auth';

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

describe('auth store', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ profile: null, loading: false });
  });

  it('login 成功后保存资料', async () => {
    vi.mocked(authApi.login).mockResolvedValue(admin);
    await useAuthStore.getState().login('admin', 'secret');
    expect(useAuthStore.getState().profile).toEqual(admin);
  });

  it('login 失败时抛错且不保存资料', async () => {
    vi.mocked(authApi.login).mockRejectedValue(new Error('401'));
    await expect(useAuthStore.getState().login('x', 'y')).rejects.toThrow('401');
    expect(useAuthStore.getState().profile).toBeNull();
  });

  it('fetchMe 成功保存资料并结束 loading', async () => {
    vi.mocked(authApi.fetchMe).mockResolvedValue(admin);
    await useAuthStore.getState().fetchMe();
    expect(useAuthStore.getState().profile).toEqual(admin);
    expect(useAuthStore.getState().loading).toBe(false);
  });

  it('fetchMe 失败（401）保持未登录并结束 loading', async () => {
    vi.mocked(authApi.fetchMe).mockRejectedValue(new Error('401'));
    await expect(useAuthStore.getState().fetchMe()).rejects.toThrow('401');
    expect(useAuthStore.getState().profile).toBeNull();
    expect(useAuthStore.getState().loading).toBe(false);
  });

  it('logout 成功后清空资料', async () => {
    useAuthStore.setState({ profile: admin });
    vi.mocked(authApi.logout).mockResolvedValue(undefined);
    await useAuthStore.getState().logout();
    expect(useAuthStore.getState().profile).toBeNull();
  });

  it('clear 直接清空资料', () => {
    useAuthStore.setState({ profile: admin });
    useAuthStore.getState().clear();
    expect(useAuthStore.getState().profile).toBeNull();
  });
});
