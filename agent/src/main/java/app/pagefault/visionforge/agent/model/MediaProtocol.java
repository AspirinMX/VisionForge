package app.pagefault.visionforge.agent.model;

/**
 * Wire protocol supported by a model route. The first stage ships the offline {@link #STUB}
 * provider only; real gateway adapters are added once the gateway contract is verified.
 */
public enum MediaProtocol {
    OPENAI_RESPONSES,
    OPENAI_IMAGES,
    ARK_IMAGES,
    ARK_VIDEO_TASKS,
    DASHSCOPE_IMAGE_MESSAGES,
    DASHSCOPE_T2I,
    DASHSCOPE_VIDEO,
    GATEWAY_CUSTOM,
    STUB
}
