import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { UserProfile } from '../api/types';
import { useAuthStore } from '../stores/auth';
import HomePage from './HomePage';

const admin: UserProfile = {
  userId: '1',
  username: 'admin',
  displayName: '管理员',
  roleId: 1,
  role: 'ADMIN',
  store: null,
  canManage: true,
  canViewCostAndProfit: true,
};

describe('HomePage', () => {
  it('展示账号、角色与权限标记', () => {
    useAuthStore.setState({ profile: admin });
    render(<HomePage />);
    expect(screen.getByText('管理员')).toBeInTheDocument();
    expect(screen.getByText('admin')).toBeInTheDocument();
    expect(screen.getByText('ADMIN')).toBeInTheDocument();
    expect(screen.getByText('可管理（导入/撤销）')).toBeInTheDocument();
    expect(screen.getByText('可查看成本与毛利')).toBeInTheDocument();
  });

  it('普通用户展示绑定门店', () => {
    useAuthStore.setState({
      profile: {
        userId: '2',
        username: 'store1user',
        displayName: '门店一用户',
        roleId: 2,
        role: 'USER',
        store: { id: '1', storeCode: 'S001', storeName: '门店一' },
        canManage: false,
        canViewCostAndProfit: false,
      },
    });
    render(<HomePage />);
    expect(screen.getByText(/S001 · 门店一/)).toBeInTheDocument();
  });

  it('未登录（profile 为 null）时不渲染内容', () => {
    useAuthStore.setState({ profile: null });
    const { container } = render(<HomePage />);
    expect(container).toBeEmptyDOMElement();
  });
});
