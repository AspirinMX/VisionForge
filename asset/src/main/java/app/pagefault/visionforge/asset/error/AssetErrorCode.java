package app.pagefault.visionforge.asset.error;

import app.pagefault.visionforge.common.error.ErrorCode;

public enum AssetErrorCode implements ErrorCode {

    ITEM_NOT_FOUND("ASSET_ITEM_NOT_FOUND", 404, "存储条目不存在"),
    ITEM_KIND_REQUIRED("ASSET_ITEM_KIND_REQUIRED", 400, "存储条目类型不能为空"),
    ITEM_KIND_UNSUPPORTED("ASSET_ITEM_KIND_UNSUPPORTED", 400, "当前只支持文本类存储条目"),
    ITEM_TITLE_INVALID("ASSET_ITEM_TITLE_INVALID", 400, "存储条目标题不能为空"),
    ITEM_TITLE_TOO_LONG("ASSET_ITEM_TITLE_TOO_LONG", 400, "存储条目标题不能超过 200 个字符"),
    ITEM_PATH_INVALID("ASSET_ITEM_PATH_INVALID", 400, "存储条目路径不合法"),
    ITEM_PATH_CONFLICT("ASSET_ITEM_PATH_CONFLICT", 409, "工作空间存储库中已存在相同路径的条目"),
    ITEM_CONTENT_EMPTY("ASSET_ITEM_CONTENT_EMPTY", 400, "存储条目内容不能为空"),
    ITEM_CONTENT_TOO_LONG("ASSET_ITEM_CONTENT_TOO_LONG", 400, "存储条目内容不能超过 1 MB"),
    ITEM_VERSION_CONFLICT("ASSET_ITEM_VERSION_CONFLICT", 409, "存储条目已被修改，请读取最新版本后重试"),
    ITEM_VERSION_NOT_FOUND("ASSET_ITEM_VERSION_NOT_FOUND", 404, "存储条目版本不存在"),
    CONVERSATION_ITEM_NOT_FOUND("ASSET_CONVERSATION_ITEM_NOT_FOUND", 404, "会话未绑定该存储条目");

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
