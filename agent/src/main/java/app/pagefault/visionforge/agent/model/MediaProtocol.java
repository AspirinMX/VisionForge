package app.pagefault.visionforge.agent.model;

/**
 * Wire protocol supported by a model route. Every media protocol except {@link #GATEWAY_CUSTOM}
 * and {@link #STUB} has a registered adapter; {@link #STUB} is a test double that is neither
 * registered nor accepted for new model routes.
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
