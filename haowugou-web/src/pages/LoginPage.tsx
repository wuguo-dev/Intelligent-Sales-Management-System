import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Form, Input } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ensureCsrfToken } from '../api/auth';
import { getProblemDetailMessage } from '../api/http';
import { useAuthStore } from '../stores/auth';
import loginIllustration from '../assets/login-illustration.svg';
import logo from '../assets/logo.svg';
import './login.css';

interface LoginFormValues {
  username: string;
  password: string;
}

export default function LoginPage() {
  const navigate = useNavigate();
  const login = useAuthStore((s) => s.login);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 登录是状态变更请求，先确保 CSRF 令牌（Cookie 已有则跳过，不发请求）。
  useEffect(() => {
    void ensureCsrfToken();
  }, []);

  const onFinish = async (values: LoginFormValues) => {
    setSubmitting(true);
    setError(null);
    try {
      await login(values.username, values.password);
      navigate('/', { replace: true });
    } catch (e) {
      setError(getProblemDetailMessage(e) ?? '登录失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-brand">
        <div className="login-brand-head">
          <img src={logo} alt="好物购" width={44} height={44} />
          <span className="login-brand-name">好物购</span>
        </div>
        <div className="login-brand-tagline">百货商场 · 多门店智能经营分析系统</div>
        <div className="login-brand-illustration">
          <img src={loginIllustration} alt="品牌视觉" />
        </div>
      </div>

      <div className="login-form-panel">
        <Card className="login-card" styles={{ body: { padding: 40 } }}>
          <div className="login-card-title">欢迎登录</div>
          <div className="login-card-subtitle">请使用您的账号登录系统</div>
          {error && (
            <Alert type="error" title={error} showIcon style={{ marginBottom: 16 }} />
          )}
          <Form<LoginFormValues> onFinish={onFinish} size="large">
            <Form.Item
              name="username"
              rules={[
                { required: true, message: '请输入用户名' },
                { max: 64, message: '用户名最长 64 个字符' },
              ]}
            >
              <Input prefix={<UserOutlined />} placeholder="用户名" autoComplete="username" />
            </Form.Item>
            <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
              <Input.Password
                prefix={<LockOutlined />}
                placeholder="密码"
                autoComplete="current-password"
              />
            </Form.Item>
            <Button type="primary" htmlType="submit" block loading={submitting}>
              登 录
            </Button>
          </Form>
        </Card>
      </div>
    </div>
  );
}
