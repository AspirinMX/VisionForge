package app.pagefault.visionforge.identity.error;

import app.pagefault.visionforge.common.error.ErrorCode;

public enum IdentityErrorCode implements ErrorCode {

    EMAIL_ALREADY_REGISTERED("IDENTITY_EMAIL_ALREADY_REGISTERED", 409, "该邮箱已注册"),
    INVALID_CREDENTIALS("IDENTITY_INVALID_CREDENTIALS", 401, "邮箱或密码错误"),
    TOKEN_INVALID("IDENTITY_TOKEN_INVALID", 401, "登录状态已失效，请重新登录"),
    USER_NOT_FOUND("IDENTITY_USER_NOT_FOUND", 404, "用户不存在"),
    SELF_ROLE_CHANGE_FORBIDDEN("IDENTITY_SELF_ROLE_CHANGE_FORBIDDEN", 409, "不能修改自己的系统角色"),
    WEAK_PASSWORD("IDENTITY_WEAK_PASSWORD", 400, "密码长度必须在 12 到 72 个字符之间"),
    INVALID_EMAIL("IDENTITY_INVALID_EMAIL", 400, "邮箱格式不正确"),
    ACCOUNT_DISABLED("IDENTITY_ACCOUNT_DISABLED", 403, "账号已被禁用");

    private final String code;
    private final int httpStatus;
    private final String message;

    IdentityErrorCode(String code, int httpStatus, String message) {
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
