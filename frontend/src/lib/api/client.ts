import {
  clearStoredSession,
  getStoredSession,
  isAccessTokenFresh,
  refreshAuthSession,
} from "@/lib/auth/token-store";

import {
  ApiError,
  apiErrorFromEnvelope,
  normalizeUnknownError,
} from "./errors";
import type { ApiResponse, TokenResponse } from "./types";

export interface ApiRequestOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  query?: Record<string, string | number | boolean | undefined | null>;
  headers?: Record<string, string>;
  idempotencyKey?: string;
  auth?: boolean;
  signal?: AbortSignal;
}

export function buildApiUrl(
  path: string,
  query?: Record<string, string | number | boolean | undefined | null>
): string {
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  if (!query) {
    return normalizedPath;
  }
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== "") {
      params.set(key, String(value));
    }
  }
  const search = params.toString();
  return search ? `${normalizedPath}?${search}` : normalizedPath;
}

export function createIdempotencyKey(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

async function fetchRefreshToken(refreshToken: string): Promise<TokenResponse | null> {
  const response = await fetch("/api/v1/auth/refresh", {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ refreshToken }),
  });

  if (!response.ok) {
    return null;
  }
  const envelope = (await response.json()) as Partial<ApiResponse<TokenResponse>>;
  if (envelope.code !== "OK" || !envelope.data) {
    return null;
  }
  return envelope.data;
}

export async function ensureValidAccessToken(): Promise<string | null> {
  const session = getStoredSession();
  if (!session) {
    return null;
  }
  if (isAccessTokenFresh(session)) {
    return session.accessToken;
  }
  const refreshed = await refreshAuthSession(fetchRefreshToken);
  return refreshed?.accessToken ?? null;
}

export async function forceRefreshAccessToken(): Promise<string | null> {
  const refreshed = await refreshAuthSession(fetchRefreshToken);
  return refreshed?.accessToken ?? null;
}

async function parseJsonSafe(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) {
    return null;
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}

async function executeWithAuthRetry(
  url: string,
  auth: boolean,
  baseHeaders: Record<string, string>,
  requestInit: Omit<RequestInit, "headers">
): Promise<Response> {
  const execute = async (tokenOverride?: string | null): Promise<Response> => {
    const requestHeaders = new Headers(baseHeaders);
    if (auth) {
      const token =
        tokenOverride !== undefined
          ? tokenOverride
          : await ensureValidAccessToken();
      if (!token) {
        throw new ApiError({
          status: 401,
          code: "COMMON_UNAUTHORIZED",
          message: "未登录或登录状态已失效",
        });
      }
      requestHeaders.set("Authorization", `Bearer ${token}`);
    }
    return fetch(url, {
      ...requestInit,
      headers: requestHeaders,
    });
  };

  let response = await execute();
  if (response.status === 401 && auth) {
    const refreshedToken = await forceRefreshAccessToken();
    if (refreshedToken) {
      response = await execute(refreshedToken);
    } else {
      clearStoredSession();
    }
  }
  return response;
}

function parseContentDispositionFilename(
  headerValue: string | null,
  fallback: string
): string {
  if (!headerValue) {
    return fallback;
  }
  const utf8Match = /filename\*=UTF-8''([^;]+)/i.exec(headerValue);
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1].trim());
    } catch {
      return utf8Match[1].trim();
    }
  }
  const asciiMatch = /filename="([^"]+)"/i.exec(headerValue) ?? /filename=([^;]+)/i.exec(headerValue);
  if (asciiMatch?.[1]) {
    return asciiMatch[1].trim();
  }
  return fallback;
}

async function throwResponseError(response: Response): Promise<never> {
  const headerTraceId = response.headers.get("X-Request-Id");
  const parsed = await parseJsonSafe(response);
  if (parsed && typeof parsed === "object") {
    throw apiErrorFromEnvelope(
      response.status,
      parsed as Partial<ApiResponse<unknown>>,
      headerTraceId
    );
  }
  if (response.status >= 502) {
    throw new ApiError({
      status: response.status,
      code: "COMMON_SERVICE_UNAVAILABLE",
      message: "后端服务暂不可用，请确认 API 服务已启动",
      traceId: headerTraceId,
    });
  }
  throw new ApiError({
    status: response.status,
    code: response.ok ? "CLIENT_INVALID_RESPONSE" : "COMMON_INTERNAL_ERROR",
    message: response.ok
      ? "服务端返回了非 JSON 响应"
      : `请求失败（HTTP ${response.status}）`,
    traceId: headerTraceId,
  });
}

export async function apiRequest<T>(
  path: string,
  options: ApiRequestOptions = {}
): Promise<T> {
  const {
    method = "GET",
    body,
    query,
    headers = {},
    idempotencyKey,
    auth = true,
    signal,
  } = options;

  const url = buildApiUrl(path, query);
  const baseHeaders: Record<string, string> = {
    Accept: "application/json",
    ...headers,
  };
  if (body !== undefined && !Object.keys(baseHeaders).some((k) => k.toLowerCase() === "content-type")) {
    baseHeaders["Content-Type"] = "application/json";
  }
  if (idempotencyKey) {
    baseHeaders["Idempotency-Key"] = idempotencyKey;
  }

  try {
    const response = await executeWithAuthRetry(url, auth, baseHeaders, {
      method,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal,
    });

    const headerTraceId = response.headers.get("X-Request-Id");
    const parsed = await parseJsonSafe(response);

    if (!parsed || typeof parsed !== "object") {
      if (response.status >= 502) {
        throw new ApiError({
          status: response.status,
          code: "COMMON_SERVICE_UNAVAILABLE",
          message: "后端服务暂不可用，请确认 API 服务已启动",
          traceId: headerTraceId,
        });
      }
      throw new ApiError({
        status: response.status,
        code: response.ok ? "CLIENT_INVALID_RESPONSE" : "COMMON_INTERNAL_ERROR",
        message: response.ok
          ? "服务端返回了非 JSON 响应"
          : `请求失败（HTTP ${response.status}）`,
        traceId: headerTraceId,
      });
    }

    const envelope = parsed as Partial<ApiResponse<T>>;
    if (!response.ok || envelope.code !== "OK") {
      throw apiErrorFromEnvelope(response.status, envelope, headerTraceId);
    }

    return envelope.data as T;
  } catch (error) {
    throw normalizeUnknownError(error);
  }
}

export async function apiDownload(
  path: string,
  options?: {
    query?: Record<string, string | number | boolean | undefined | null>;
    fallbackFilename?: string;
    signal?: AbortSignal;
  }
): Promise<string> {
  const url = buildApiUrl(path, options?.query);
  try {
    const response = await executeWithAuthRetry(
      url,
      true,
      { Accept: "text/csv, application/octet-stream, */*" },
      { method: "GET", signal: options?.signal }
    );
    if (!response.ok) {
      await throwResponseError(response);
    }
    const filename = parseContentDispositionFilename(
      response.headers.get("Content-Disposition"),
      options?.fallbackFilename ?? "export.csv"
    );
    const blob = await response.blob();
    const objectUrl = URL.createObjectURL(blob);
    try {
      const link = document.createElement("a");
      link.href = objectUrl;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      link.remove();
    } finally {
      window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
    }
    return filename;
  } catch (error) {
    throw normalizeUnknownError(error);
  }
}

export function apiGet<T>(
  path: string,
  options?: Omit<ApiRequestOptions, "method" | "body">
): Promise<T> {
  return apiRequest<T>(path, { ...options, method: "GET" });
}

export function apiPost<T>(
  path: string,
  body?: unknown,
  options?: Omit<ApiRequestOptions, "method" | "body">
): Promise<T> {
  return apiRequest<T>(path, { ...options, method: "POST", body });
}

export function apiPut<T>(
  path: string,
  body?: unknown,
  options?: Omit<ApiRequestOptions, "method" | "body">
): Promise<T> {
  return apiRequest<T>(path, { ...options, method: "PUT", body });
}

export function apiPatch<T>(
  path: string,
  body?: unknown,
  options?: Omit<ApiRequestOptions, "method" | "body">
): Promise<T> {
  return apiRequest<T>(path, { ...options, method: "PATCH", body });
}

export function apiDelete<T>(
  path: string,
  options?: Omit<ApiRequestOptions, "method" | "body">
): Promise<T> {
  return apiRequest<T>(path, { ...options, method: "DELETE" });
}

export function putToPresignedUrl(params: {
  url: string;
  file: Blob;
  requiredHeaders: Record<string, string>;
  onProgress?: (progress: number) => void;
  signal?: AbortSignal;
}): Promise<void> {
  const { url, file, requiredHeaders, onProgress, signal } = params;

  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("PUT", url, true);

    for (const [key, value] of Object.entries(requiredHeaders)) {
      xhr.setRequestHeader(key, value);
    }

    const handleAbort = () => {
      xhr.abort();
      reject(
        new ApiError({
          status: 0,
          code: "CLIENT_ABORTED",
          message: "文件上传已取消",
        })
      );
    };

    if (signal) {
      if (signal.aborted) {
        handleAbort();
        return;
      }
      signal.addEventListener("abort", handleAbort, { once: true });
    }

    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && onProgress) {
        onProgress(Math.min(100, Math.round((event.loaded / event.total) * 100)));
      }
    };

    xhr.onload = () => {
      signal?.removeEventListener("abort", handleAbort);
      if (xhr.status >= 200 && xhr.status < 300) {
        onProgress?.(100);
        resolve();
        return;
      }
      reject(
        new ApiError({
          status: xhr.status,
          code: "ASSET_STORAGE_UPLOAD_FAILED",
          message: `文件上传失败（HTTP ${xhr.status}），请稍后重试`,
        })
      );
    };

    xhr.onerror = () => {
      signal?.removeEventListener("abort", handleAbort);
      reject(
        new ApiError({
          status: 503,
          code: "ASSET_STORAGE_UNREACHABLE",
          message: "文件上传服务暂时不可达，请检查网络连接",
        })
      );
    };

    xhr.send(file);
  });
}
