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

    private final ModelDefinitionMapper modelDefinitionMapper;
    private final ApiKeyCipher apiKeyCipher;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public List<ModelDefinition> list() {
        return modelDefinitionMapper.selectList(
                Wrappers.<ModelDefinition>lambdaQuery()
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

    @Transactional
    @BizLog(
            type = "AGENT_MODEL",
            subType = "CREATE",
            bizNo = "#result.id",
            success = "模型「{{#result.displayName}}」已创建",
            fail = "创建模型失败：{{#errorMessage}}",
            sync = true)
    public ModelDefinition create(ModelDefinitionInput input) {
        String displayName = validateText(input.displayName(), "显示名称", DISPLAY_NAME_MAX_LENGTH);
        String baseUrl = validateBaseUrl(input.baseUrl());
        String modelId = validateText(input.modelId(), "模型 ID", MODEL_ID_MAX_LENGTH);
        if (input.apiKey() == null || input.apiKey().isBlank()) {
            throw new BusinessException(AgentErrorCode.MODEL_API_KEY_REQUIRED);
        }
        int maxInputTokens = validateTokens(input.maxInputTokens(), "最大输入", MAX_INPUT_LIMIT);
        int maxOutputTokens = validateTokens(input.maxOutputTokens(), "最大输出", MAX_OUTPUT_LIMIT);
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
        model.setReasoningEffort(input.reasoningEffort());
        model.setMaxInputTokens(maxInputTokens);
        model.setMaxOutputTokens(maxOutputTokens);
        model.setEnabled(enabled);
        model.setCreatedAt(now);
        model.setUpdatedAt(now);

        if (makeDefault || (enabled && !hasDefault())) {
            clearDefault(now);
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
        if (input.reasoningEffort() != null) {
            model.setReasoningEffort(input.reasoningEffort());
        }
        if (input.maxInputTokens() != null) {
            model.setMaxInputTokens(
                    validateTokens(input.maxInputTokens(), "最大输入", MAX_INPUT_LIMIT));
        }
        if (input.maxOutputTokens() != null) {
            model.setMaxOutputTokens(
                    validateTokens(input.maxOutputTokens(), "最大输出", MAX_OUTPUT_LIMIT));
        }
        if (input.enabled() != null) {
            model.setEnabled(input.enabled());
        }
        if (input.defaultModel() != null) {
            if (input.defaultModel()) {
                if (!model.isEnabled()) {
                    throw new BusinessException(AgentErrorCode.MODEL_CONFIG_INVALID, "禁用的模型不能设为默认");
                }
                clearDefault(now);
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
        clearDefault(now);
        model.setDefaultModel(true);
        model.setUpdatedAt(now);
        modelDefinitionMapper.updateById(model);
        return model;
    }

    public ModelDefinition requireDefaultEnabled() {
        ModelDefinition model = findDefaultEnabledOrNull();
        if (model == null) {
            throw new BusinessException(AgentErrorCode.MODEL_NOT_CONFIGURED);
        }
        return model;
    }

    public ModelDefinition findDefaultEnabledOrNull() {
        ModelDefinition model = modelDefinitionMapper.selectOne(
                Wrappers.<ModelDefinition>lambdaQuery()
                        .eq(ModelDefinition::isDefaultModel, true)
                        .eq(ModelDefinition::isEnabled, true));
        if (model != null) {
            return model;
        }
        return modelDefinitionMapper.selectOne(
                Wrappers.<ModelDefinition>lambdaQuery()
                        .eq(ModelDefinition::isEnabled, true)
                        .orderByAsc(ModelDefinition::getCreatedAt)
                        .last("LIMIT 1"));
    }

    public ModelDefinition resolveForRun(UUID preferredModelId) {
        if (preferredModelId != null) {
            ModelDefinition preferred = modelDefinitionMapper.selectById(preferredModelId);
            if (preferred != null && preferred.isEnabled()) {
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
                model.getMaxInputTokens(),
                model.getMaxOutputTokens());
    }

    private boolean hasDefault() {
        return modelDefinitionMapper.selectCount(
                Wrappers.<ModelDefinition>lambdaQuery()
                        .eq(ModelDefinition::isDefaultModel, true)) > 0;
    }

    private void clearDefault(Instant now) {
        modelDefinitionMapper.update(null, Wrappers.<ModelDefinition>lambdaUpdate()
                .eq(ModelDefinition::isDefaultModel, true)
                .set(ModelDefinition::isDefaultModel, false)
                .set(ModelDefinition::getUpdatedAt, now));
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
}
