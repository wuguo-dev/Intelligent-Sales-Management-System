import { create } from 'zustand';
import * as authApi from '../api/auth';
import type { UserProfile } from '../api/types';

interface AuthState {
  /** 当前登录者；null = 未登录。身份只存内存，服务端会话 Cookie 才是权威。 */
  profile: UserProfile | null;
  /** fetchMe 恢复会话中。 */
  loading: boolean;
  login: (username: string, password: string) => Promise<void>;
  fetchMe: () => Promise<void>;
  logout: () => Promise<void>;
  clear: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  profile: null,
  loading: false,

  login: async (username, password) => {
    const profile = await authApi.login(username, password);
    set({ profile });
  },

  fetchMe: async () => {
    set({ loading: true });
    try {
      const profile = await authApi.fetchMe();
      set({ profile });
    } finally {
      set({ loading: false });
    }
  },

  logout: async () => {
    await authApi.logout();
    set({ profile: null });
  },

  clear: () => set({ profile: null }),
}));
