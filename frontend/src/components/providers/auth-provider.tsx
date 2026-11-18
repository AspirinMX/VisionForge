"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useSyncExternalStore,
  type ReactNode,
} from "react";

import { ensureValidAccessToken } from "@/lib/api/client";
import { authApi } from "@/lib/api/endpoints";
import { normalizeUnknownError } from "@/lib/api/errors";
import type {
  LoginRequest,
  RegisterRequest,
  UserResponse,
} from "@/lib/api/types";
import {
  clearStoredSession,
  getStoredSession,
  setStoredSession,
  subscribeSession,
  updateStoredUser,
} from "@/lib/auth/token-store";

export type AuthStatus = "loading" | "authenticated" | "unauthenticated";

interface AuthContextValue {
  status: AuthStatus;
  user: UserResponse | null;
  login: (request: LoginRequest) => Promise<UserResponse>;
  register: (request: RegisterRequest) => Promise<UserResponse>;
  logout: () => Promise<void>;
  refreshUser: () => Promise<UserResponse | null>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

const emptySubscribe = () => () => {};
const getClientSnapshot = () => true;
const getServerClientSnapshot = () => false;
const getServerSessionSnapshot = () => null;

export function AuthProvider({ children }: { children: ReactNode }) {
  const isClient = useSyncExternalStore(
    emptySubscribe,
    getClientSnapshot,
    getServerClientSnapshot
  );
  const session = useSyncExternalStore(
    subscribeSession,
    getStoredSession,
    getServerSessionSnapshot
  );

  const verifiedUserIdRef = useRef<string | null>(null);

  useEffect(() => {
    if (!isClient) {
      return;
    }
    if (!session) {
      verifiedUserIdRef.current = null;
      return;
    }
    if (session.user && verifiedUserIdRef.current === session.userId) {
      return;
    }

    let cancelled = false;

    const verify = async () => {
      try {
        const token = await ensureValidAccessToken();
        if (!token) {
          if (!cancelled) {
            clearStoredSession();
          }
          return;
        }
        const me = await authApi.me();
        if (!cancelled) {
          verifiedUserIdRef.current = me.id;
          updateStoredUser(me);
        }
      } catch (error) {
        if (cancelled) {
          return;
        }
        const apiError = normalizeUnknownError(error);
        if (apiError.isUnauthorized) {
          verifiedUserIdRef.current = null;
          clearStoredSession();
        } else if (session.user) {
          verifiedUserIdRef.current = session.userId;
        }
      }
    };

    void verify();
    return () => {
      cancelled = true;
    };
  }, [isClient, session]);

  const login = useCallback(async (request: LoginRequest) => {
    const result = await authApi.login(request);
    verifiedUserIdRef.current = result.user.id;
    setStoredSession(result.token, result.user);
    return result.user;
  }, []);

  const register = useCallback(async (request: RegisterRequest) => {
    return authApi.register(request);
  }, []);

  const logout = useCallback(async () => {
    const current = getStoredSession();
    try {
      if (current?.refreshToken) {
        await authApi.logout({ refreshToken: current.refreshToken });
      }
    } catch {
      // Clear local session regardless of server revocation outcome.
    } finally {
      verifiedUserIdRef.current = null;
      clearStoredSession();
    }
  }, []);

  const refreshUser = useCallback(async () => {
    const token = await ensureValidAccessToken();
    if (!token) {
      verifiedUserIdRef.current = null;
      clearStoredSession();
      return null;
    }
    const me = await authApi.me();
    verifiedUserIdRef.current = me.id;
    updateStoredUser(me);
    return me;
  }, []);

  const status: AuthStatus = !isClient
    ? "loading"
    : !session
      ? "unauthenticated"
      : session.user
        ? "authenticated"
        : "loading";

  const user = isClient && session?.user ? session.user : null;

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      user,
      login,
      register,
      logout,
      refreshUser,
    }),
    [status, user, login, register, logout, refreshUser]
  );

  return <AuthContext value={value}>{children}</AuthContext>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used inside AuthProvider");
  }
  return ctx;
}
