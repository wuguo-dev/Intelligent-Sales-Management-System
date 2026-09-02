import { useEffect } from 'react';
import { useAppStore } from '../stores/app';
import { useAuthStore } from '../stores/auth';

/**
 * 管理员进入业务页时把 URL 里的门店同步进 app store（选择器与菜单随 URL 显示）。
 * 普通用户门店来自 profile，不动 app store。
 */
export function useStoreSync(storeId: number): void {
  const isAdmin = useAuthStore((s) => s.profile?.store == null);
  const currentStoreId = useAppStore((s) => s.currentStoreId);
  const selectStore = useAppStore((s) => s.selectStore);

  useEffect(() => {
    if (isAdmin && currentStoreId !== storeId) {
      selectStore(storeId);
    }
  }, [isAdmin, currentStoreId, storeId, selectStore]);
}
