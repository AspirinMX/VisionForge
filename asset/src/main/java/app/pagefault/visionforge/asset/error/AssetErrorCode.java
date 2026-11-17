package app.pagefault.visionforge.asset.error;

import app.pagefault.visionforge.common.error.ErrorCode;

public enum AssetErrorCode implements ErrorCode {

    FILE_NOT_FOUND("ASSET_FILE_NOT_FOUND", 404, "会话文件不存在"),
    FILE_KIND_REQUIRED("ASSET_FILE_KIND_REQUIRED", 400, "文件类型不能为空"),
    FILE_KIND_UNSUPPORTED("ASSET_FILE_KIND_UNSUPPORTED", 400, "当前只支持文本类文件"),
    FILE_TITLE_INVALID("ASSET_FILE_TITLE_INVALID", 400, "文件标题不能为空"),
    FILE_TITLE_TOO_LONG("ASSET_FILE_TITLE_TOO_LONG", 400, "文件标题不能超过 200 个字符"),
    FILE_PATH_INVALID("ASSET_FILE_PATH_INVALID", 400, "文件路径不合法"),
    FILE_PATH_CONFLICT("ASSET_FILE_PATH_CONFLICT", 409, "当前会话中已存在相同路径的文件"),
    FILE_CONTENT_EMPTY("ASSET_FILE_CONTENT_EMPTY", 400, "文件内容不能为空"),
    FILE_CONTENT_TOO_LONG("ASSET_FILE_CONTENT_TOO_LONG", 400, "文件内容不能超过 1 MB"),
    FILE_VERSION_CONFLICT("ASSET_FILE_VERSION_CONFLICT", 409, "文件已被修改，请读取最新版本后重试"),
    FILE_VERSION_NOT_FOUND("ASSET_FILE_VERSION_NOT_FOUND", 404, "文件版本不存在"),
    CONVERSATION_NOT_FOUND("ASSET_CONVERSATION_NOT_FOUND", 404, "会话不存在"),
    ASSET_NOT_FOUND("ASSET_NOT_FOUND", 404, "资产不存在"),
    ASSET_NOT_READY("ASSET_NOT_READY", 409, "资产尚未可用"),
    ASSET_KIND_REQUIRED("ASSET_KIND_REQUIRED", 400, "资产类型不能为空"),
    ASSET_FILENAME_REQUIRED("ASSET_FILENAME_REQUIRED", 400, "文件名不能为空"),
    ASSET_MIME_UNSUPPORTED("ASSET_MIME_UNSUPPORTED", 400, "不支持该文件类型"),
    ASSET_SIZE_INVALID("ASSET_SIZE_INVALID", 400, "文件大小必须大于 0"),
    ASSET_SIZE_TOO_LARGE("ASSET_SIZE_TOO_LARGE", 413, "文件超过允许的大小"),
    UPLOAD_SESSION_NOT_FOUND("ASSET_UPLOAD_SESSION_NOT_FOUND", 404, "上传会话不存在"),
    UPLOAD_SESSION_EXPIRED("ASSET_UPLOAD_SESSION_EXPIRED", 410, "上传会话已过期"),
    UPLOAD_SESSION_NOT_ACTIVE("ASSET_UPLOAD_SESSION_NOT_ACTIVE", 409, "上传会话已结束"),
    UPLOAD_NOT_COMPLETED("ASSET_UPLOAD_NOT_COMPLETED", 409, "对象尚未上传完成"),
    UPLOAD_SIZE_MISMATCH("ASSET_UPLOAD_SIZE_MISMATCH", 409, "上传对象大小不一致"),
    UPLOAD_CONTENT_TYPE_MISMATCH("ASSET_UPLOAD_CONTENT_TYPE_MISMATCH", 409, "上传对象类型不一致"),
    STORAGE_NOT_CONFIGURED("ASSET_STORAGE_NOT_CONFIGURED", 503, "对象存储未配置"),
    STORAGE_OPERATION_FAILED("ASSET_STORAGE_OPERATION_FAILED", 502, "对象存储操作失败");

    private final String code;
    private final int httpStatus;
    private final String message;

    AssetErrorCode(String code, int httpStatus, String message) {
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
