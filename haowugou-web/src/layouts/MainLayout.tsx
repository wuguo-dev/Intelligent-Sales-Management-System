import { LogoutOutlined, ShoppingOutlined, UserOutlined } from '@ant-design/icons';
import { Avatar, Dropdown, Layout, Menu, Space, Tag, Typography } from 'antd';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAppStore } from '../stores/app';
import { useAuthStore } from '../stores/auth';
import StoreSelector from './StoreSelector';

export default function MainLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const profile = useAuthStore((s) => s.profile);
  const logout = useAuthStore((s) => s.logout);
  const currentStoreId = useAppStore((s) => s.currentStoreId);
  const clearStore = useAppStore((s) => s.clearStore);

  // 业务页菜单：普通用户不可见；管理员未选门店时禁用
  const canManage = profile?.canManage ?? false;
  const base = profile?.store
    ? `/stores/${profile.store.id}`
    : currentStoreId
      ? `/stores/${currentStoreId}`
      : null;

  const onLogout = async () => {
    try {
      await logout();
    } finally {
      clearStore();
      navigate('/login', { replace: true });
    }
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Header
        style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16 }}
      >
        <Space>
          <ShoppingOutlined style={{ color: '#fff', fontSize: 20 }} />
          <Typography.Title level={4} style={{ color: '#fff', margin: 0 }}>
            好物购
          </Typography.Title>
        </Space>
        <Space>
          <StoreSelector />
          <Dropdown
            menu={{
              items: [
                {
                  key: 'identity',
                  disabled: true,
                  label: (
                    <Space>
                      <Tag color={profile?.canManage ? 'gold' : 'blue'}>{profile?.role}</Tag>
                      {profile?.store ? profile.store.storeName : '未绑定门店（管理员）'}
                    </Space>
                  ),
                },
                { type: 'divider' },
                { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: onLogout },
              ],
            }}
          >
            <Space style={{ cursor: 'pointer', color: '#fff' }}>
              <Avatar size="small" icon={<UserOutlined />} />
              {profile?.displayName ?? profile?.username ?? '未登录'}
            </Space>
          </Dropdown>
        </Space>
      </Layout.Header>
      <Layout>
        {canManage && (
          <Layout.Sider theme="light" width={200}>
            <Menu
              mode="inline"
              selectedKeys={[location.pathname]}
              items={[
                {
                  type: 'group',
                  label: '导入管理',
                  children: [
                    {
                      key: `${base ?? ''}/imports/inventory`,
                      label: '初始库存导入',
                      disabled: !base,
                    },
                    {
                      key: `${base ?? ''}/imports/sales`,
                      label: '每日销售导入',
                      disabled: !base,
                    },
                    {
                      key: `${base ?? ''}/import-batches`,
                      label: '导入批次',
                      disabled: !base,
                    },
                  ],
                },
              ]}
              onClick={({ key }) => navigate(key)}
            />
          </Layout.Sider>
        )}
        <Layout.Content style={{ padding: 24 }}>
          <Outlet />
        </Layout.Content>
      </Layout>
    </Layout>
  );
}
