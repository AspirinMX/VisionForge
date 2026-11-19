package app.pagefault.visionforge.agent.model;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.common.audit.BizLog;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ModelDefinitionService {

    private static final int DISPLAY_NAME_MAX_LENGTH = 100;
    private static final int BASE_URL_MAX_LENGTH = 500;
    private static final int MODEL_ID_MAX_LENGTH = 200;
    private static final int MAX_INPUT_LIMIT = 2_000_000;
    private static final int MAX_OUTPUT_LIMIT = 1_000_000;
    private static final long MAX_PRICE_UNITS = 1_000_000_000L;

    private final ModelDefinitionMapper modelDefinitionMapper;
    private final ApiKeyCipher apiKeyCipher;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public List<ModelDefinition> list() {
        return modelDefinitionMapper.selectList(
                Wrappers.<ModelDefinition>lambdaQuery()
                        .orderByAsc(ModelDefinition::getCapabilityType)
                        .orderByDesc(ModelDefinition::isDefaultModel)
                        .orderByAsc(ModelDefinition::getCreatedAt));
    }

    /**
     * Enabled models only, for the end-user catalog. Disabled routes never appear here.
     */
    public List<ModelDefinition> listEnabled(ModelCapabilityType capabilityType) {
        return modelDefinitionMapper.selectList(
                Wrappers.<ModelDefinition>lambdaQuery()
                        .eq(ModelDefinition::isEnabled, true)
                        .eq(capabilityType != null,
                                ModelDefinition::getCapabilityType, capabilityType)
                        .orderByAsc(ModelDefinition::getCapabilityType)
                        .orderByDesc(ModelDefinition::isDefaultModel)
                        .orderByAsc(ModelDefinition::getCreatedAt));
    }

    public ModelDefinition get(UUID id) {
        ModelDefinition model = id == null ? null : modelDefinitionMapper.selectById(id);
        if (model == null) {
            throw new BusinessException(AgentErrorCode.MODEL_NOT_FOUND);
        }
        return model;
    }

    public ModelDefinition requireEnabled(UUID id, ModelCapabilityType capabilityType) {
        ModelDefinition model = get(id);
        if (!model.isEnabled() || model.getCapabilityType() != capabilityType) {
            throw new BusinessException(
                    AgentErrorCode.MODEL_CONFIG_INVALID,
                    "模型不可用或能力类型与请求不一致");
        }
        return model;
    }

    @Transactional
    @BizLog(
            type = "AGENT_MODEL",
            subType = "CREATE",
            bizNo = "#result.id",
            success = "模型「{{#result.displayName}}」已创建",
            fail = "创建模型失败：{{#errorMessage}}",
            sync = true)
    public ModelDefinition create(ModelDefinitionInput input) {
        ModelCapabilityType capability = input.capabilityType() == null
                ? ModelCapabilityType.CHAT
                : input.capabilityType();
        String displayName = validateText(input.displayName(), "显示名称", DISPLAY_NAME_MAX_LENGTH);
        String baseUrl = validateBaseUrl(input.baseUrl());
        String modelId = validateText(input.modelId(), "模型 ID", MODEL_ID_MAX_LENGTH);
        if (input.apiKey() == null || input.apiKey().isBlank()) {
            throw new BusinessException(AgentErrorCode.MODEL_API_KEY_REQUIRED);
        }
        Integer maxInputTokens = null;
        Integer maxOutputTokens = null;
        if (capability == ModelCapabilityType.CHAT) {
            maxInputTokens = validateTokens(input.maxInputTokens(), "最大输入", MAX_INPUT_LIMIT);
            maxOutputTokens = validateTokens(input.maxOutputTokens(), "最大输出", MAX_OUTPUT_LIMIT);
        }
        MediaProtocol protocol = input.protocol() == null
                ? defaultProtocol(capability)
                : input.protocol();
        ExecutionMode executionMode = input.executionMode() == null
                ? defaultExecutionMode(protocol)
                : input.executionMode();
        validateRoute(capability, protocol, executionMode);
        long priceUnits = validatePrice(input.priceUnits());
        boolean supportsImageInput = input.supportsImageInput() == null
                || input.supportsImageInput();
        boolean enabled = input.enabled() == null || input.enabled();
        boolean makeDefault = Boolean.TRUE.equals(input.defaultModel());
        if (makeDefault && !enabled) {
            throw new BusinessException(AgentErrorCode.MODEL_CONFIG_INVALID, "禁用的模型不能设为默认");
        }

        Instant now = clock.instant();
        ModelDefinition model = new ModelDefinition();
        model.setId(idGenerator.nextId());
        model.setDisplayName(displayName);
        model.setBaseUrl(baseUrl);
        model.setApiKeyCiphertext(apiKeyCipher.encrypt(input.apiKey().trim()));
        model.setModelId(modelId);
        applyCreateReasoning(model, capability, input);
        model.setReasoningReplay(input.reasoningReplay() == null
                ? ReasoningReplayPolicy.ENCRYPTED
                : input.reasoningReplay());
        model.setCapabilityType(capability);
        model.setProtocol(protocol);
        model.setExecutionMode(executionMode);
        model.setPriceUnits(priceUnits);
        model.setSupportsImageInput(supportsImageInput);
        model.setMaxInputTokens(maxInputTokens);
        model.setMaxOutputTokens(maxOutputTokens);
        model.setEnabled(enabled);
        model.setCreatedAt(now);
        model.setUpdatedAt(now);

        if (makeDefault || (enabled && !hasDefault(capability))) {
            clearDefault(now, capability);
            model.setDefaultModel(true);
        }
        modelDefinitionMapper.insert(model);
        return model;
    }

    @Transactional
    @BizLog(
            type = "AGENT_MODEL",
            subType = "UPDATE",
            bizNo = "#id",
            success = "模型 {{#id}} 已更新",
            fail = "更新模型失败：{{#errorMessage}}",
            sync = true)
    public ModelDefinition update(UUID id, ModelDefinitionInput input) {
        ModelDefinition model = get(id);
        ModelCapabilityType previousCapability = model.getCapabilityType();
        Instant now = clock.instant();

        if (input.displayName() != null) {
            model.setDisplayName(validateText(input.displayName(), "显示名称", DISPLAY_NAME_MAX_LENGTH));
        }
        if (input.baseUrl() != null) {
            model.setBaseUrl(validateBaseUrl(input.baseUrl()));
        }
        if (input.modelId() != null) {
            model.setModelId(validateText(input.modelId(), "模型 ID", MODEL_ID_MAX_LENGTH));
        }
        if (input.apiKey() != null) {
            if (input.apiKey().isBlank()) {
                throw new BusinessException(AgentErrorCode.MODEL_API_KEY_REQUIRED);
            }
            model.setApiKeyCiphertext(apiKeyCipher.encrypt(input.apiKey().trim()));
        }
        if (input.reasoningReplay() != null) {
            model.setReasoningReplay(input.reasoningReplay());
        }
        if (input.capabilityType() != null) {
            model.setCapabilityType(input.capabilityType());
        }
        applyUpdateReasoning(model, input);
        if (input.protocol() != null) {
            model.setProtocol(input.protocol());
        }
        if (input.executionMode() != null) {
            model.setExecutionMode(input.executionMode());
        }
        if (input.priceUnits() != null) {
            model.setPriceUnits(validatePrice(input.priceUnits()));
        }
        if (input.supportsImageInput() != null) {
            model.setSupportsImageInput(input.supportsImageInput());
        }
        if (input.maxInputTokens() != null) {
            model.setMaxInputTokens(
                    validateTokens(input.maxInputTokens(), "最大输入", MAX_INPUT_LIMIT));
        }
        if (input.maxOutputTokens() != null) {
            model.setMaxOutputTokens(
                    validateTokens(input.maxOutputTokens(), "最大输出", MAX_OUTPUT_LIMIT));
        }
        if (model.getCapabilityType() == ModelCapabilityType.CHAT
                && (model.getMaxInputTokens() == null || model.getMaxOutputTokens() == null)) {
            throw new BusinessException(
                    AgentErrorCode.MODEL_CONFIG_INVALID, "文本模型必须配置最大输入和输出 Token");
        }
        if (model.getProtocol() == null) {
            model.setProtocol(defaultProtocol(model.getCapabilityType()));
        }
        if (model.getExecutionMode() == null) {
            model.setExecutionMode(defaultExecutionMode(model.getProtocol()));
        }
        validateRoute(
                model.getCapabilityType(), model.getProtocol(), model.getExecutionMode());
        if (input.enabled() != null) {
            model.setEnabled(input.enabled());
        }
        if (previousCapability != model.getCapabilityType()) {
            clearDefault(now, previousCapability);
            model.setDefaultModel(false);
            if (model.isEnabled() && !hasDefault(model.getCapabilityType())) {
                clearDefault(now, model.getCapabilityType());
                model.setDefaultModel(true);
            }
        }
        if (input.defaultModel() != null) {
            if (input.defaultModel()) {
                if (!model.isEnabled()) {
                    throw new BusinessException(AgentErrorCode.MODEL_CONFIG_INVALID, "禁用的模型不能设为默认");
                }
                clearDefault(now, model.getCapabilityType());
                model.setDefaultModel(true);
            } else {
                model.setDefaultModel(false);
            }
        }
        if (!model.isEnabled()) {
            model.setDefaultModel(false);
        }
        model.setUpdatedAt(now);
        modelDefinitionMapper.updateById(model);
        return model;
    }

    @Transactional
    @BizLog(
            type = "AGENT_MODEL",
            subType = "SET_DEFAULT",
            bizNo = "#id",
            success = "模型 {{#id}} 已设为默认",
            fail = "设置默认模型失败：{{#errorMessage}}",
            sync = true)
    public ModelDefinition setDefault(UUID id) {
        ModelDefinition model = get(id);
        if (!model.isEnabled()) {
            throw new BusinessException(AgentErrorCode.MODEL_CONFIG_INVALID, "禁用的模型不能设为默认");
        }
        Instant now = clock.instant();
        clearDefault(now, model.getCapabilityType());
        model.setDefaultModel(true);
        model.setUpdatedAt(now);
        modelDefinitionMapper.updateById(model);
        return model;
    }

    public ModelDefinition requireDefaultEnabled() {
        return requireDefaultEnabled(ModelCapabilityType.CHAT);
    }

    public ModelDefinition requireDefaultEnabled(ModelCapabilityType capabilityType) {
        ModelDefinition model = findDefaultEnabledOrNull(capabilityType);
        if (model == null) {
            throw new BusinessException(
                    AgentErrorCode.MODEL_NOT_CONFIGURED,
                    "尚未配置可用的 " + capabilityType + " 模型");
        }
        return model;
    }

    public ModelDefinition findDefaultEnabledOrNull() {
        return findDefaultEnabledOrNull(ModelCapabilityType.CHAT);
    }

    public ModelDefinition findDefaultEnabledOrNull(ModelCapabilityType capabilityType) {
        ModelDefinition model = modelDefinitionMapper.selectOne(
                Wrappers.<ModelDefinition>lambdaQuery()
                        .eq(ModelDefinition::getCapabilityType, capabilityType)
                        .eq(ModelDefinition::isDefaultModel, true)
                        .eq(ModelDefinition::isEnabled, true));
        if (model != null) {
            return model;
        }
        return modelDefinitionMapper.selectOne(
                Wrappers.<ModelDefinition>lambdaQuery()
                        .eq(ModelDefinition::getCapabilityType, capabilityType)
                        .eq(ModelDefinition::isEnabled, true)
                        .orderByAsc(ModelDefinition::getCreatedAt)
                        .last("LIMIT 1"));
    }

    public ModelDefinition resolveForRun(UUID preferredModelId) {
        if (preferredModelId != null) {
            ModelDefinition preferred = modelDefinitionMapper.selectById(preferredModelId);
            if (preferred != null
                    && preferred.isEnabled()
                    && preferred.getCapabilityType() == ModelCapabilityType.CHAT) {
                return preferred;
            }
        }
        return requireDefaultEnabled();
    }

    public ModelProviderSettings settings(ModelDefinition model) {
        return new ModelProviderSettings(
                model.getId(),
                model.getBaseUrl(),
                apiKeyCipher.decrypt(model.getApiKeyCiphertext()),
                model.getModelId(),
                model.getReasoningEffort(),
                model.getMaxInputTokens() == null ? 0 : model.getMaxInputTokens(),
                model.getMaxOutputTokens() == null ? 0 : model.getMaxOutputTokens());
    }

    private boolean hasDefault(ModelCapabilityType capabilityType) {
        return modelDefinitionMapper.selectCount(
                Wrappers.<ModelDefinition>lambdaQuery()
                        .eq(ModelDefinition::getCapabilityType, capabilityType)
                        .eq(ModelDefinition::isDefaultModel, true)) > 0;
    }

    private void clearDefault(Instant now, ModelCapabilityType capabilityType) {
        modelDefinitionMapper.update(null, Wrappers.<ModelDefinition>lambdaUpdate()
                .eq(ModelDefinition::getCapabilityType, capabilityType)
                .eq(ModelDefinition::isDefaultModel, true)
                .set(ModelDefinition::isDefaultModel, false)
                .set(ModelDefinition::getUpdatedAt, now));
    }

    private ExecutionMode defaultExecutionMode(MediaProtocol protocol) {
        return switch (protocol) {
            case ARK_VIDEO_TASKS, DASHSCOPE_T2I, DASHSCOPE_VIDEO -> ExecutionMode.REMOTE_TASK;
            default -> ExecutionMode.INLINE_RESULT;
        };
    }

    private MediaProtocol defaultProtocol(ModelCapabilityType capabilityType) {
        return switch (capabilityType) {
            case CHAT -> MediaProtocol.OPENAI_RESPONSES;
            case IMAGE -> MediaProtocol.OPENAI_IMAGES;
            case VIDEO -> MediaProtocol.ARK_VIDEO_TASKS;
        };
    }

    private void validateRoute(
            ModelCapabilityType capabilityType,
            MediaProtocol protocol,
            ExecutionMode executionMode) {
        if (protocol == MediaProtocol.STUB || protocol == MediaProtocol.GATEWAY_CUSTOM) {
            throw new BusinessException(
                    AgentErrorCode.MODEL_CONFIG_INVALID,
                    "协议 " + protocol + " 尚未开放为模型路由");
        }
        boolean valid = switch (capabilityType) {
            case CHAT -> protocol == MediaProtocol.OPENAI_RESPONSES
                    && executionMode == ExecutionMode.INLINE_RESULT;
            case IMAGE -> protocol != MediaProtocol.OPENAI_RESPONSES
                    && protocol != MediaProtocol.ARK_VIDEO_TASKS
                    && protocol != MediaProtocol.DASHSCOPE_VIDEO;
            case VIDEO -> protocol != MediaProtocol.OPENAI_RESPONSES
                    && protocol != MediaProtocol.OPENAI_IMAGES
                    && protocol != MediaProtocol.ARK_IMAGES
                    && protocol != MediaProtocol.DASHSCOPE_IMAGE_MESSAGES
                    && protocol != MediaProtocol.DASHSCOPE_T2I;
        };
        boolean executionValid = switch (protocol) {
            case OPENAI_IMAGES, ARK_IMAGES, DASHSCOPE_IMAGE_MESSAGES ->
                    executionMode == ExecutionMode.INLINE_RESULT;
            case ARK_VIDEO_TASKS, DASHSCOPE_T2I, DASHSCOPE_VIDEO ->
                    executionMode == ExecutionMode.REMOTE_TASK;
            default -> true;
        };
        if (!valid || !executionValid) {
            throw new BusinessException(
                    AgentErrorCode.MODEL_CONFIG_INVALID, "模型能力、协议与执行方式不匹配");
        }
    }

    private long validatePrice(Long value) {
        long price = value == null ? 0 : value;
        if (price < 0 || price > MAX_PRICE_UNITS) {
            throw new BusinessException(
                    AgentErrorCode.MODEL_CONFIG_INVALID,
                    "单任务价格必须在 0 到 " + MAX_PRICE_UNITS + " 之间");
        }
        return price;
    }

    private String validateText(String value, String field, int maxLength) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > maxLength) {
            throw new BusinessException(
                    AgentErrorCode.MODEL_CONFIG_INVALID,
                    field + "不能为空且不能超过 " + maxLength + " 个字符");
        }
        return text;
    }

    private String validateBaseUrl(String value) {
        String url = validateText(value, "Base URL", BASE_URL_MAX_LENGTH);
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null
                    || (!"http".equalsIgnoreCase(uri.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("unsupported scheme");
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    AgentErrorCode.MODEL_CONFIG_INVALID, "Base URL 必须是 http 或 https 地址");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private int validateTokens(Integer value, String field, int limit) {
        if (value == null || value <= 0 || value > limit) {
            throw new BusinessException(
                    AgentErrorCode.MODEL_CONFIG_INVALID,
                    field + "必须在 1 到 " + limit + " 之间");
        }
        return value;
    }

    private void applyCreateReasoning(
            ModelDefinition model, ModelCapabilityType capability, ModelDefinitionInput input) {
        if (capability != ModelCapabilityType.CHAT) {
            model.setReasoningEffort(null);
            model.setSupportedReasoningEfforts("[]");
            return;
        }
        ReasoningEffort effort = input.reasoningEffort();
        List<ReasoningEffort> supported = input.supportedReasoningEfforts() != null
                ? ReasoningEffortCodec.normalize(input.supportedReasoningEfforts())
                : (effort != null ? List.of(effort) : List.of());
        validateReasoningSelection(effort, supported);
        model.setReasoningEffort(effort);
        model.setSupportedReasoningEfforts(ReasoningEffortCodec.encode(supported));
    }

    private void applyUpdateReasoning(ModelDefinition model, ModelDefinitionInput input) {
        if (model.getCapabilityType() != ModelCapabilityType.CHAT) {
            model.setReasoningEffort(null);
            model.setSupportedReasoningEfforts("[]");
            return;
        }
        boolean touchedReasoning = input.reasoningEffort() != null
                || input.supportedReasoningEfforts() != null
                || Boolean.TRUE.equals(input.clearReasoningEffort());
        if (!touchedReasoning) {
            return;
        }
        List<ReasoningEffort> supported = input.supportedReasoningEfforts() != null
                ? ReasoningEffortCodec.normalize(input.supportedReasoningEfforts())
                : ReasoningEffortCodec.decode(model.getSupportedReasoningEfforts());
        ReasoningEffort effort = Boolean.TRUE.equals(input.clearReasoningEffort())
                ? null
                : (input.reasoningEffort() != null ? input.reasoningEffort() : model.getReasoningEffort());
        validateReasoningSelection(effort, supported);
        model.setReasoningEffort(effort);
        model.setSupportedReasoningEfforts(ReasoningEffortCodec.encode(supported));
    }

    private void validateReasoningSelection(
            ReasoningEffort effort, List<ReasoningEffort> supported) {
        if (effort != null && !supported.contains(effort)) {
            throw new BusinessException(
                    AgentErrorCode.MODEL_CONFIG_INVALID,
                    "默认推理强度必须在支持的推理档位范围内");
        }
    }
}
