"use client";

import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  type ReactNode,
} from "react";

import { useAuth } from "@/components/providers/auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { accountApi } from "@/lib/api/endpoints";
import type { ApiError } from "@/lib/api/errors";
import type { CreditSummaryResponse } from "@/lib/api/types";

interface CreditContextValue {
  credit: CreditSummaryResponse | null;
  loading: boolean;
  error: ApiError | null;
  refreshCredit: () => void;
  refreshCreditSilent: () => Promise<void>;
}

const CreditContext = createContext<CreditContextValue | null>(null);

export function CreditProvider({ children }: { children: ReactNode }) {
  const { status, user } = useAuth();
  const enabled = status === "authenticated" && Boolean(user);

  const { data, loading, error, reload, mutate } = useAsyncData(
    () => accountApi.credit(),
    user ? `account-credit:${user.id}` : "account-credit:anon",
    {
      enabled,
      errorMessage: "加载额度余额失败",
    }
  );

  const refreshCreditSilent = useCallback(async () => {
    if (!enabled) {
      return;
    }
    try {
      const fresh = await accountApi.credit();
      mutate(() => fresh);
    } catch {
      // Preserve existing balance pill when silent refresh fails transiently.
    }
  }, [enabled, mutate]);

  const value = useMemo<CreditContextValue>(
    () => ({
      credit: data,
      loading,
      error,
      refreshCredit: reload,
      refreshCreditSilent,
    }),
    [data, error, loading, refreshCreditSilent, reload]
  );

  return <CreditContext value={value}>{children}</CreditContext>;
}

export function useOptionalCredit(): CreditContextValue | null {
  return useContext(CreditContext);
}

export function useCredit(): CreditContextValue {
  const context = useContext(CreditContext);
  if (!context) {
    throw new Error("useCredit must be used inside CreditProvider");
  }
  return context;
}
