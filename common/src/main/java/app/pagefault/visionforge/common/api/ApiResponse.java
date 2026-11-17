package app.pagefault.visionforge.common.api;

import java.time.Instant;

import app.pagefault.visionforge.common.error.ErrorCode;
import app.pagefault.visionforge.common.logging.TraceContext;

/**
 * Uniform JSON envelope for every HTTP response.
 *
 * <p>{@code code} is {@code OK} for successful responses and a namespaced error code
 * otherwise. The transport status is carried by HTTP, not repeated in the payload.
 * {@code traceId} mirrors the {@code X-Request-Id} header and can be used for support.
 */
public record ApiResponse<T>(
        String code,
        String message,
        T data,
        String traceId,
        Instant timestamp) {

    public static final String SUCCESS_CODE = "OK";
    public static final String SUCCESS_MESSAGE = "成功";

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data, TraceContext.currentTraceId(), Instant.now());
    }

    public static ApiResponse<Void> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return error(errorCode, errorCode.message());
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.code(), message, null, TraceContext.currentTraceId(), Instant.now());
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message, T data) {
        return new ApiResponse<>(errorCode.code(), message, data, TraceContext.currentTraceId(), Instant.now());
    }
}
