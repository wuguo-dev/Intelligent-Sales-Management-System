/** 与后端 `AuthenticatedUserResponse.StoreResponse` 1:1。 */
export interface StoreView {
  id: number;
  storeCode: string;
  storeName: string;
}

export type UserRole = 'ADMIN' | 'USER';

/** 与后端 `AuthenticatedUserResponse` 1:1（login 与 me 共用）。 */
export interface UserProfile {
  userId: number;
  username: string;
  displayName: string;
  roleId: number; // 1 管理员，2 普通用户——仅展示用，权限判定用 canManage/canViewCostAndProfit
  role: UserRole;
  store: StoreView | null; // null = 管理员
  canManage: boolean; // 是否可执行导入、撤销等写操作
  canViewCostAndProfit: boolean; // 是否可看到含税成本价与毛利字段
}

/** 后端统一错误体（RFC 7807 Problem Detail）。 */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
}

export interface CsrfTokenResponse {
  token: string;
  headerName: string;
  parameterName: string;
}