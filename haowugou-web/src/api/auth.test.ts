import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as auth from './auth';

const http = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));

vi.mock('./http', () => ({
  default: { get: http.get, post: http.post },
  readCookie: vi.fn(),
  CSRF_COOKIE_NAME: 'XSRF-TOKEN',
}));

import { readCookie } from './http';

describe('ensureCsrfToken', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('Cookie 缺失时请求 GET /api/auth/csrf', async () => {
    vi.mocked(readCookie).mockReturnValue(null);
    http.get.mockResolvedValue({
      data: { token: 't', headerName: 'X-XSRF-TOKEN', parameterName: '_csrf' },
    });
    await auth.ensureCsrfToken();
    expect(http.get).toHaveBeenCalledWith('/api/auth/csrf');
  });

  it('Cookie 已存在时不发请求', async () => {
    vi.mocked(readCookie).mockReturnValue('has-token');
    await auth.ensureCsrfToken();
    expect(http.get).not.toHaveBeenCalled();
  });
});

describe('login / fetchMe / logout', () => {
  it('login 提交用户名密码并返回资料', async () => {
    const profile = {
      userId: 1,
      username: 'admin',
      displayName: '管理员',
      roleId: 1,
      role: 'ADMIN',
      store: null,
      canManage: true,
      canViewCostAndProfit: true,
    };
    http.post.mockResolvedValue({ data: profile });
    await expect(auth.login('admin', 'secret')).resolves.toEqual(profile);
    expect(http.post).toHaveBeenCalledWith('/api/auth/login', {
      username: 'admin',
      password: 'secret',
    });
  });

  it('fetchMe 返回当前登录者资料', async () => {
    http.get.mockResolvedValue({ data: { userId: 2 } });
    await auth.fetchMe();
    expect(http.get).toHaveBeenCalledWith('/api/auth/me');
  });

  it('logout 提交登出', async () => {
    http.post.mockResolvedValue({ status: 204 });
    await auth.logout();
    expect(http.post).toHaveBeenCalledWith('/api/auth/logout');
  });
});