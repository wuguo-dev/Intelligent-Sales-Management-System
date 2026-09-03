import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { UserProfile } from '../api/types';
import { useAuthStore } from '../stores/auth';
import { storeScopedLoader } from './index';

vi.mock('../api/auth', () => ({ fetchMe: vi.fn() }));

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
const user: UserProfile = {
  userId: '2',
  username: 'store1user',
  displayName: '门店查询员',
  roleId: 2,
  role: 'USER',
  store: { id: '7', storeCode: 'S007', storeName: '门店七' },
  canManage: false,
  canViewCostAndProfit: false,
};

function loaderArgs(pathname: string, storeId: string) {
  return {
    request: new Request(`http://localhost${pathname}`),
    params: { storeId },
  };
}

describe('storeScopedLoader', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ profile: null });
  });

  it('管理员放行', async () => {
    useAuthStore.setState({ profile: admin });
    expect(await storeScopedLoader(loaderArgs('/stores/5/import-batches', '5'))).toBeNull();
  });

  it('普通用户访问自家门店放行', async () => {
    useAuthStore.setState({ profile: user });
    expect(await storeScopedLoader(loaderArgs('/stores/7/import-batches', '7'))).toBeNull();
  });

  it('普通用户访问别家门店重定向到自家', async () => {
    useAuthStore.setState({ profile: user });
    const result = await storeScopedLoader(loaderArgs('/stores/3/import-batches', '3'));
    expect(result).not.toBeNull();
    expect((result as Response).headers.get('Location')).toBe('/stores/7/import-batches');
  });

  it('无登录态时恢复会话后放行', async () => {
    vi.mocked(authApi.fetchMe).mockResolvedValue(user);
    expect(await storeScopedLoader(loaderArgs('/stores/7/imports/sales', '7'))).toBeNull();
  });

  it('无登录态且 me 401 时重定向登录页', async () => {
    vi.mocked(authApi.fetchMe).mockRejectedValue(new Error('401'));
    const result = await storeScopedLoader(loaderArgs('/stores/7/import-batches', '7'));
    expect((result as Response).headers.get('Location')).toBe('/login');
  });
});
