import { LogoutOutlined, UserOutlined } from '@ant-design/icons';
import { Avatar, Dropdown, Layout, Space, Tag, Typography } from 'antd';
import { Outlet, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../stores/auth';

export default function MainLayout() {
  const navigate = useNavigate();
  const profile = useAuthStore((s) => s.profile);
  const logout = useAuthStore((s) => s.logout);

  const onLogout = async () => {
    try {
      await logout();
    } finally {
      navigate('/login', { replace: true });
    }
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Header
        style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}
      >
        <Typography.Title level={4} style={{ color: '#fff', margin: 0 }}>
          好物购
        </Typography.Title>
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
      </Layout.Header>
      <Layout.Content style={{ padding: 24 }}>
        <Outlet />
      </Layout.Content>
    </Layout>
  );
}
