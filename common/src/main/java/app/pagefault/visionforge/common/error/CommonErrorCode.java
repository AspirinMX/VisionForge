package app.pagefault.visionforge.common.error;

/**
 * Error codes shared by every module.
 *
 * <p>Domain-specific codes belong to their owning module and must use a module prefix,
 * for example {@code IDENTITY_EMAIL_ALREADY_REGISTERED}.
 */
public enum CommonErrorCode implements ErrorCode {

    INVALID_ARGUMENT("COMMON_INVALID_ARGUMENT", 400, "请求参数不合法"),
    UNAUTHORIZED("COMMON_UNAUTHORIZED", 401, "未认证或登录状态已失效"),
    FORBIDDEN("COMMON_FORBIDDEN", 403, "没有执行该操作的权限"),
    NOT_FOUND("COMMON_NOT_FOUND", 404, "请求的资源不存在"),
    METHOD_NOT_ALLOWED("COMMON_METHOD_NOT_ALLOWED", 405, "请求方法不被支持"),
    CONFLICT("COMMON_CONFLICT", 409, "请求与当前资源状态冲突"),
    UNSUPPORTED_MEDIA_TYPE("COMMON_UNSUPPORTED_MEDIA_TYPE", 415, "不支持的内容类型"),
    RATE_LIMITED("COMMON_RATE_LIMITED", 429, "请求过于频繁，请稍后重试"),
    INTERNAL_ERROR("COMMON_INTERNAL_ERROR", 500, "系统内部错误，请稍后重试"),
    SERVICE_UNAVAILABLE("COMMON_SERVICE_UNAVAILABLE", 503, "服务暂时不可用，请稍后重试");

    private final String code;
    private final int httpStatus;
    private final String message;

    CommonErrorCode(String code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String message() {
        return message;
    }
}
