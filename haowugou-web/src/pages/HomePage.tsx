import { Card, Descriptions, Tag } from 'antd';
import { useAuthStore } from '../stores/auth';

export default function HomePage() {
  const profile = useAuthStore((s) => s.profile);
  if (!profile) {
    return null;
  }
  return (
    <Card title="当前用户">
      <Descriptions column={1} bordered>
        <Descriptions.Item label="账号ID">{profile.userId}</Descriptions.Item>
        <Descriptions.Item label="登录名">{profile.username}</Descriptions.Item>
        <Descriptions.Item label="展示名">{profile.displayName}</Descriptions.Item>
        <Descriptions.Item label="角色">
          <Tag color={profile.canManage ? 'gold' : 'blue'}>{profile.role}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label="绑定门店">
          {profile.store
            ? `${profile.store.storeCode} · ${profile.store.storeName}`
            : '未绑定（管理员）'}
        </Descriptions.Item>
        <Descriptions.Item label="权限">
          {profile.canManage && <Tag color="gold">可管理（导入/撤销）</Tag>}
          {profile.canViewCostAndProfit && <Tag color="purple">可查看成本与毛利</Tag>}
        </Descriptions.Item>
      </Descriptions>
    </Card>
  );
}
