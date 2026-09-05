import http, { CSRF_COOKIE_NAME, readCookie } from './http';
import type { CsrfTokenResponse, UserProfile } from './types';

/**
 * 确保存在 CSRF 令牌：Cookie 里没有 XSRF-TOKEN 时先 GET /api/auth/csrf
 * （该请求顺带种下 Cookie）。登录/登出前的状态变更请求都要令牌。
 */
export async function ensureCsrfToken(): Promise<void> {
  if (readCookie(CSRF_COOKIE_NAME)) {
    return;
  }
  await http.get<CsrfTokenResponse>('/api/auth/csrf');
}

/** 账号密码登录；失败（401）时抛出 axios 错误，文案从 Problem Detail 提取。 */
export async function login(username: string, password: string): Promise<UserProfile> {
  const { data } = await http.post<UserProfile>('/api/auth/login', { username, password });
  return data;
}

/** 查当前登录者资料；未登录 401 抛错，由路由守卫决定跳转。 */
export async function fetchMe(): Promise<UserProfile> {
  const { data } = await http.get<UserProfile>('/api/auth/me');
  return data;
}

/** 登出；204 视为成功。 */
export async function logout(): Promise<void> {
  await http.post('/api/auth/logout');
}