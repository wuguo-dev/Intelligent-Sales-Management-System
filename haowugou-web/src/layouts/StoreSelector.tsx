import { Select } from 'antd';
import { useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { swapStoreIdInPath } from '../router/path';
import { useAppStore } from '../stores/app';
import { useAuthStore } from '../stores/auth';

/** 顶栏门店选择器：仅管理员渲染；切换时改写 URL 的 storeId 段。 */
export default function StoreSelector() {
  const navigate = useNavigate();
  const location = useLocation();
  const profile = useAuthStore((s) => s.profile);
  const stores = useAppStore((s) => s.stores);
  const currentStoreId = useAppStore((s) => s.currentStoreId);
  const loadStores = useAppStore((s) => s.loadStores);
  const selectStore = useAppStore((s) => s.selectStore);

  useEffect(() => {
    if (profile && !profile.store) {
      void loadStores();
    }
  }, [profile, loadStores]);

  if (!profile || profile.store) {
    return null;
  }

  return (
    <Select
      style={{ width: 220 }}
      placeholder="未选门店"
      value={currentStoreId ?? undefined}
      options={stores.map((s) => ({ value: s.id, label: s.storeName }))}
      onChange={(id) => {
        selectStore(id);
        navigate(swapStoreIdInPath(location.pathname, id));
      }}
    />
  );
}
