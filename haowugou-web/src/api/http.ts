import axios, { type InternalAxiosRequestConfig } from 'axios';
import type { ProblemDetail } from './types';

/** 与后端 CookieCsrfTokenRepository 的 Cookie 名一致。 */
export const CSRF_COOKIE_NAME = 'XSRF-TOKEN';
/** CSRF 令牌应放入的请求头（后端 csrfTokenResponse.headerName）。 */
export const CSRF_HEADER_NAME = 'X-XSRF-TOKEN';

export function readCookie(name: string): string | null {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = document.cookie.match(new RegExp(`(?:^|; )${escaped}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : null;
}

/** 请求拦截器：从 Cookie 读 CSRF 令牌回填请求头；无令牌时不动。 */
export function attachCsrfHeader(
  config: InternalAxiosRequestConfig,
): InternalAxiosRequestConfig {
  const token = readCookie(CSRF_COOKIE_NAME);
  if (token) {
    config.headers.set(CSRF_HEADER_NAME, token);
  }
  return config;
}

export function isProblemDetail(body: unknown): body is ProblemDetail {
  return (
    typeof body === 'object' &&
    body !== null &&
    'status' in body &&
    typeof (body as ProblemDetail).status === 'number'
  );
}

/** 从 axios 错误里提取 Problem Detail 的展示文案；非此类错误返回 null。 */
export function getProblemDetailMessage(error: unknown): string | null {
  if (!axios.isAxiosError(error) || !error.response) {
    return null;
  }
  const data = error.response.data;
  if (isProblemDetail(data)) {
    return data.detail ?? data.title ?? null;
  }
  return null;
}

/** 当前路径是否登录页——登录页上的 401 是登录失败本身，不做全局跳转。 */
export function isLoginPath(pathname: string): boolean {
  return pathname === '/login' || pathname.startsWith('/login/');
}

const http = axios.create({ withCredentials: true });

http.interceptors.request.use(attachCsrfHeader);

// 会话中途失效（401）：非登录页统一踢回登录页；登录页的 401 由页面自行展示错误。
http.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (
      axios.isAxiosError(error) &&
      error.response?.status === 401 &&
      !isLoginPath(window.location.pathname)
    ) {
      window.location.assign('/login');
    }
    return Promise.reject(error);
  },
);

export default http;