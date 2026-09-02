import { create } from 'zustand';
import { listStores } from '../api/stores';
import type { StoreView } from '../api/types';

interface AppState {
  /** 门店列表（仅管理员加载）。 */
  stores: StoreView[];
  /** 管理员当前选中门店；普通用户恒为 null（从 profile.store 派生）。 */
  currentStoreId: number | null;
  loadStores: () => Promise<void>;
  selectStore: (id: number) => void;
  clearStore: () => void;
}

export const useAppStore = create<AppState>((set) => ({
  stores: [],
  currentStoreId: null,

  loadStores: async () => {
    try {
      set({ stores: await listStores() });
    } catch {
      // 失败静默置空：选择器展示空数据，不阻塞页面
      set({ stores: [] });
    }
  },

  selectStore: (id) => set({ currentStoreId: id }),

  clearStore: () => set({ currentStoreId: null, stores: [] }),
}));
