package app.pagefault.visionforge.agent.error;

import app.pagefault.visionforge.common.error.ErrorCode;

public enum AgentErrorCode implements ErrorCode {

    MODEL_NOT_FOUND("AGENT_MODEL_NOT_FOUND", 404, "模型配置不存在"),
    MODEL_NOT_CONFIGURED("AGENT_MODEL_NOT_CONFIGURED", 409, "尚未配置可用的文本模型"),
    MODEL_CONFIG_INVALID("AGENT_MODEL_CONFIG_INVALID", 400, "模型配置不合法"),
    MODEL_API_KEY_REQUIRED("AGENT_MODEL_API_KEY_REQUIRED", 400, "创建模型时必须提供 API Key"),
    MODEL_PROVIDER_ERROR("AGENT_MODEL_PROVIDER_ERROR", 502, "模型网关调用失败"),
    MODEL_RESPONSE_INVALID("AGENT_MODEL_RESPONSE_INVALID", 502, "模型网关返回了无法解析的响应"),
    CONVERSATION_NOT_FOUND("AGENT_CONVERSATION_NOT_FOUND", 404, "会话不存在"),
    CONVERSATION_BUSY("AGENT_CONVERSATION_BUSY", 409, "该会话正在生成回复，请等待当前轮次结束"),
    AGENT_RUN_NOT_FOUND("AGENT_RUN_NOT_FOUND", 404, "执行记录不存在"),
    AGENT_RUN_START_FAILED("AGENT_RUN_START_FAILED", 503, "对话执行启动失败，请稍后重试"),
    MESSAGE_EMPTY("AGENT_MESSAGE_EMPTY", 400, "消息内容不能为空"),
    MESSAGE_TOO_LONG("AGENT_MESSAGE_TOO_LONG", 400, "消息内容超过模型允许的输入长度"),
    AGENT_RUN_INVALID_STATE("AGENT_RUN_INVALID_STATE", 409, "执行记录状态不允许该操作"),
    STORYBOARD_INVALID("AGENT_STORYBOARD_INVALID", 400, "分镜 JSON 不合法"),
    STORYBOARD_SHOT_NOT_FOUND("AGENT_STORYBOARD_SHOT_NOT_FOUND", 404, "分镜镜头不存在"),
    GENERATION_NOT_FOUND("AGENT_GENERATION_NOT_FOUND", 404, "生成任务不存在"),
    GENERATION_PROMPT_EMPTY("AGENT_GENERATION_PROMPT_EMPTY", 400, "生成提示词不能为空"),
    GENERATION_CAPABILITY_UNSUPPORTED("AGENT_GENERATION_CAPABILITY_UNSUPPORTED", 400, "模型能力不支持该生成请求"),
    GENERATION_INPUT_INVALID("AGENT_GENERATION_INPUT_INVALID", 400, "生成输入不合法"),
    GENERATION_IDEMPOTENCY_CONFLICT("AGENT_GENERATION_IDEMPOTENCY_CONFLICT", 409, "同一幂等键对应了不同的生成请求"),
    GENERATION_STATE_CONFLICT("AGENT_GENERATION_STATE_CONFLICT", 409, "生成任务状态不允许该操作"),
    GENERATION_PROVIDER_UNSUPPORTED("AGENT_GENERATION_PROVIDER_UNSUPPORTED", 400, "尚未实现该媒体协议"),
    GENERATION_PROVIDER_ERROR("AGENT_GENERATION_PROVIDER_ERROR", 502, "媒体网关调用失败"),
    GENERATION_START_FAILED("AGENT_GENERATION_START_FAILED", 503, "生成任务启动失败，请稍后重试"),
    GENERATION_CANCEL_UNSUPPORTED("AGENT_GENERATION_CANCEL_UNSUPPORTED", 409, "当前任务不支持取消"),
    GENERATION_ARCHIVE_FAILED("AGENT_GENERATION_ARCHIVE_FAILED", 502, "生成结果归档失败"),
    SKILL_NOT_FOUND("AGENT_SKILL_NOT_FOUND", 404, "创作 skill 不存在"),
    TOOL_NOT_FOUND("AGENT_TOOL_NOT_FOUND", 404, "工具不存在"),
    TOOL_ARGUMENTS_INVALID("AGENT_TOOL_ARGUMENTS_INVALID", 400, "工具参数不合法"),
    TOOL_CONVERSATION_REQUIRED("AGENT_TOOL_CONVERSATION_REQUIRED", 409, "该工具必须在会话中执行"),
    TOOL_INVOCATION_IN_PROGRESS("AGENT_TOOL_INVOCATION_IN_PROGRESS", 409, "同一工具调用正在执行"),
    TOOL_EXECUTION_FAILED("AGENT_TOOL_EXECUTION_FAILED", 500, "工具执行失败");

    private final String code;
    private final int httpStatus;
    private final String message;

    AgentErrorCode(String code, int httpStatus, String message) {
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
