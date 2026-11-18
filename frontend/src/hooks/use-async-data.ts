"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { normalizeUnknownError, type ApiError } from "@/lib/api/errors";

export interface UseAsyncDataOptions {
  enabled?: boolean;
  errorMessage?: string;
}

interface AsyncDataState<T> {
  data: T | null;
  error: ApiError | null;
  baseKey: string | null;
  settledKey: string | null;
}

export interface UseAsyncDataResult<T> {
  data: T | null;
  error: ApiError | null;
  loading: boolean;
  reload: () => void;
  mutate: (updater: (prev: T | null) => T | null) => void;
}

export function useAsyncData<T>(
  fetcher: () => Promise<T>,
  queryKey: string,
  options: UseAsyncDataOptions = {}
): UseAsyncDataResult<T> {
  const { enabled = true, errorMessage = "加载数据失败" } = options;

  const [nonce, setNonce] = useState(0);
  const [state, setState] = useState<AsyncDataState<T>>({
    data: null,
    error: null,
    baseKey: null,
    settledKey: null,
  });

  const activeKey = enabled ? `${queryKey}#${nonce}` : null;
  const fetcherRef = useRef(fetcher);
  const errorMessageRef = useRef(errorMessage);

  useEffect(() => {
    fetcherRef.current = fetcher;
    errorMessageRef.current = errorMessage;
  });

  useEffect(() => {
    if (!activeKey) {
      return;
    }

    let cancelled = false;
    Promise.resolve()
      .then(() => fetcherRef.current())
      .then(
        (data) => {
          if (!cancelled) {
            setState({
              data,
              error: null,
              baseKey: queryKey,
              settledKey: activeKey,
            });
          }
        },
        (err) => {
          if (!cancelled) {
            setState((prev) => ({
              data: prev.baseKey === queryKey ? prev.data : null,
              error: normalizeUnknownError(err, errorMessageRef.current),
              baseKey: queryKey,
              settledKey: activeKey,
            }));
          }
        }
      );

    return () => {
      cancelled = true;
    };
  }, [activeKey, queryKey]);

  const reload = useCallback(() => {
    setNonce((prev) => prev + 1);
  }, []);

  const mutate = useCallback(
    (updater: (prev: T | null) => T | null) => {
      setState((prev) => ({
        ...prev,
        baseKey: queryKey,
        data: updater(prev.baseKey === queryKey ? prev.data : null),
      }));
    },
    [queryKey]
  );

  const isSameKey = state.baseKey === queryKey;

  return {
    data: enabled && isSameKey ? state.data : null,
    error: enabled && isSameKey ? state.error : null,
    loading: enabled && state.settledKey !== activeKey,
    reload,
    mutate,
  };
}
