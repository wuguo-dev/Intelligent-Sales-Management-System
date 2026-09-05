import { AxiosHeaders, type InternalAxiosRequestConfig } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import {
  attachCsrfHeader,
  CSRF_COOKIE_NAME,
  CSRF_HEADER_NAME,
  getProblemDetailMessage,
  isLoginPath,
  readCookie,
} from './http';

function makeConfig(): InternalAxiosRequestConfig {
  return { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
}

function clearCookies(): void {
  document.cookie.split(';').forEach((c) => {
    const name = c.trim().split('=')[0];
    document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
  });
}

describe('readCookie', () => {
  beforeEach(clearCookies);

  it('Cookie 存在时返回解码后的值', () => {
    document.cookie = 'a=1';
    document.cookie = 'b=hello%20world';
    expect(readCookie('b')).toBe('hello world');
  });

  it('Cookie 不存在时返回 null', () => {
    document.cookie = 'a=1';
    expect(readCookie('nope')).toBeNull();
  });
});

describe('attachCsrfHeader', () => {
  beforeEach(clearCookies);

  it('有 XSRF-TOKEN Cookie 时回填 X-XSRF-TOKEN 请求头', () => {
    document.cookie = `${CSRF_COOKIE_NAME}=abc123`;
    const config = attachCsrfHeader(makeConfig());
    expect(config.headers.get(CSRF_HEADER_NAME)).toBe('abc123');
  });

  it('无 Cookie 时不设置请求头', () => {
    document.cookie = 'a=1';
    const config = attachCsrfHeader(makeConfig());
    expect(config.headers.get(CSRF_HEADER_NAME)).toBeUndefined();
  });
});

describe('getProblemDetailMessage', () => {
  it('从 Problem Detail 响应提取 detail', () => {
    const error = {
      isAxiosError: true,
      response: { data: { title: '登录失败', status: 401, detail: '账号或密码错误' } },
    };
    expect(getProblemDetailMessage(error)).toBe('账号或密码错误');
  });

  it('detail 缺失时退回 title', () => {
    const error = {
      isAxiosError: true,
      response: { data: { title: '登录失败', status: 401 } },
    };
    expect(getProblemDetailMessage(error)).toBe('登录失败');
  });

  it('非 axios 错误返回 null', () => {
    expect(getProblemDetailMessage(new Error('boom'))).toBeNull();
  });
});

describe('isLoginPath', () => {
  it('登录页路径返回 true，其余返回 false', () => {
    expect(isLoginPath('/login')).toBe(true);
    expect(isLoginPath('/login/')).toBe(true);
    expect(isLoginPath('/')).toBe(false);
  });
});