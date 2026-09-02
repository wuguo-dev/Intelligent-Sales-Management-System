import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { UserProfile } from '../api/types';
import { useAuthStore } from '../stores/auth';
import { protectedLoader } from './index';

// mock 掉 API 层：store 的 fetchMe action 会调用这里的假 fetchMe
vi.mock('../api/auth', () => ({ fetchMe: vi.fn() }));

import * as authApi from '../api/auth';

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

describe('protectedLoader', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ profile: null });
  });

  it('已有资料时不查 me 直接放行', async () => {
    const fetchMe = vi.fn();
    useAuthStore.setState({ profile: admin, fetchMe });
    const result = await protectedLoader();
    expect(result).toBeNull();
    expect(fetchMe).not.toHaveBeenCalled();
  });

  it('无资料时查 me 恢复会话', async () => {
    vi.mocked(authApi.fetchMe).mockResolvedValue(admin);
    const result = await protectedLoader();
    expect(result).toBeNull();
    expect(authApi.fetchMe).toHaveBeenCalled();
    expect(useAuthStore.getState().profile).toEqual(admin);
  });

  it('me 返回 401 时重定向到 /login', async () => {
    vi.mocked(authApi.fetchMe).mockRejectedValue(new Error('401'));
    const result = await protectedLoader();
    expect(result).not.toBeNull();
    expect((result as Response).headers.get('Location')).toBe('/login');
  });
});
