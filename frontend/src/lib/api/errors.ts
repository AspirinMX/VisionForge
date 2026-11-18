import type { ApiResponse } from "./types";

export type FieldErrorMap = Record<string, string>;

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly traceId: string | null;
  readonly timestamp: string | null;
  readonly fieldErrors: FieldErrorMap;
  readonly rawData: unknown;

  constructor(params: {
    status: number;
    code: string;
    message: string;
    traceId?: string | null;
    timestamp?: string | null;
    fieldErrors?: FieldErrorMap;
    rawData?: unknown;
  }) {
    super(params.message);
    this.name = "ApiError";
    this.status = params.status;
    this.code = params.code;
    this.traceId = params.traceId ?? null;
    this.timestamp = params.timestamp ?? null;
    this.fieldErrors = params.fieldErrors ?? {};
    this.rawData = params.rawData ?? null;
  }

  get isValidationError(): boolean {
    return (
      this.code === "COMMON_INVALID_ARGUMENT" &&
      Object.keys(this.fieldErrors).length > 0
    );
  }

  get isUnauthorized(): boolean {
    return this.status === 401 || this.code === "COMMON_UNAUTHORIZED";
  }

  get isForbidden(): boolean {
    return this.status === 403 || this.code === "COMMON_FORBIDDEN";
  }

  get isNotFound(): boolean {
    return (
      this.status === 404 ||
      this.code === "COMMON_NOT_FOUND" ||
      this.code.endsWith("_NOT_FOUND")
    );
  }

  get isConflict(): boolean {
    return (
      this.status === 409 ||
      this.code === "COMMON_CONFLICT" ||
      this.code.includes("CONFLICT") ||
      this.code === "AGENT_CONVERSATION_BUSY"
    );
  }
}

export function extractFieldErrors(data: unknown): FieldErrorMap {
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    return {};
  }
  const result: FieldErrorMap = {};
  for (const [key, value] of Object.entries(data)) {
    if (typeof value === "string" && value.trim().length > 0) {
      result[key] = value;
    }
  }
  return result;
}

export function apiErrorFromEnvelope(
  status: number,
  envelope: Partial<ApiResponse<unknown>>,
  headerTraceId?: string | null
): ApiError {
  const code =
    typeof envelope.code === "string" && envelope.code.length > 0
      ? envelope.code
      : status >= 500
        ? "COMMON_INTERNAL_ERROR"
        : "COMMON_INVALID_ARGUMENT";
  const message =
    typeof envelope.message === "string" && envelope.message.length > 0
      ? envelope.message
      : `请求失败（HTTP ${status}）`;
  const traceId =
    typeof envelope.traceId === "string" && envelope.traceId.length > 0
      ? envelope.traceId
      : headerTraceId ?? null;

  const fieldErrors =
    code === "COMMON_INVALID_ARGUMENT"
      ? extractFieldErrors(envelope.data)
      : {};

  return new ApiError({
    status,
    code,
    message,
    traceId,
    timestamp: typeof envelope.timestamp === "string" ? envelope.timestamp : null,
    fieldErrors,
    rawData: envelope.data,
  });
}

export function normalizeUnknownError(
  error: unknown,
  fallbackMessage = "请求未能完成，请稍后重试"
): ApiError {
  if (error instanceof ApiError) {
    return error;
  }
  if (error instanceof DOMException && error.name === "AbortError") {
    return new ApiError({
      status: 0,
      code: "CLIENT_ABORTED",
      message: "操作已取消",
    });
  }
  if (error instanceof Error) {
    const isNetwork =
      error.name === "TypeError" || error.message.includes("Failed to fetch");
    return new ApiError({
      status: isNetwork ? 503 : 500,
      code: isNetwork ? "CLIENT_NETWORK_ERROR" : "CLIENT_UNEXPECTED_ERROR",
      message: isNetwork
        ? "无法连接服务端接口，请确认后端 API 服务已启动"
        : error.message || fallbackMessage,
    });
  }
  return new ApiError({
    status: 500,
    code: "CLIENT_UNEXPECTED_ERROR",
    message: fallbackMessage,
  });
}
