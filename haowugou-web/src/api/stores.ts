import http from './http';
import type { StoreView } from './types';

/** 全部已启用门店（后端仅管理员可用；普通用户从不调用）。 */
export async function listStores(): Promise<StoreView[]> {
  const { data } = await http.get<StoreView[]>('/api/stores');
  return data;
}
