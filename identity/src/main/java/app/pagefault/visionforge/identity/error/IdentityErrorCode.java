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
    ACCOUNT_DISABLED("IDENTITY_ACCOUNT_DISABLED", 403, "账号已被禁用"),
    WORKSPACE_NOT_FOUND("IDENTITY_WORKSPACE_NOT_FOUND", 404, "工作空间不存在"),
    WORKSPACE_ACCESS_DENIED("IDENTITY_WORKSPACE_ACCESS_DENIED", 403, "无权访问该工作空间"),
    WORKSPACE_OWNER_REQUIRED("IDENTITY_WORKSPACE_OWNER_REQUIRED", 403, "需要工作空间所有者权限"),
    WORKSPACE_ADMIN_REQUIRED("IDENTITY_WORKSPACE_ADMIN_REQUIRED", 403, "需要工作空间管理员权限"),
    WORKSPACE_ARCHIVED("IDENTITY_WORKSPACE_ARCHIVED", 409, "工作空间已归档"),
    NO_WORKSPACE_AVAILABLE("IDENTITY_NO_WORKSPACE_AVAILABLE", 409, "当前账号没有可用的工作空间"),
    MEMBER_ALREADY_EXISTS("IDENTITY_MEMBER_ALREADY_EXISTS", 409, "该用户已经是工作空间成员"),
    MEMBER_NOT_FOUND("IDENTITY_MEMBER_NOT_FOUND", 404, "工作空间成员不存在"),
    CANNOT_REMOVE_OWNER("IDENTITY_CANNOT_REMOVE_OWNER", 409, "不能移除工作空间所有者"),
    CANNOT_CHANGE_OWNER_ROLE("IDENTITY_CANNOT_CHANGE_OWNER_ROLE", 409, "不能修改工作空间所有者的角色，请先转让所有权"),
    INVALID_WORKSPACE_ROLE("IDENTITY_INVALID_WORKSPACE_ROLE", 400, "不能授予该工作空间角色");

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
