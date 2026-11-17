package app.pagefault.visionforge.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

class ModelDefinitionServiceTest {

    private static final UUID MODEL_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e2f");
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");

    private ModelDefinitionMapper mapper;
    private ApiKeyCipher cipher;
    private ModelDefinitionService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ModelDefinition.class);
        mapper = mock(ModelDefinitionMapper.class);
        cipher = new ApiKeyCipher(Base64.getEncoder().encodeToString(new byte[32]));
        AtomicLong sequence = new AtomicLong();
        IdGenerator idGenerator = () -> new UUID(0, sequence.incrementAndGet());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ModelDefinitionService(mapper, cipher, idGenerator, clock);
    }

    @Test
    void createEncryptsApiKeyAndMakesFirstModelDefault() {
        when(mapper.selectCount(any())).thenReturn(0L);

        ModelDefinition model = service.create(input("sk-secret", true));

        assertThat(model.isDefaultModel()).isTrue();
        assertThat(model.isEnabled()).isTrue();
        assertThat(model.getApiKeyCiphertext()).isNotEqualTo("sk-secret");
        assertThat(cipher.decrypt(model.getApiKeyCiphertext())).isEqualTo("sk-secret");
        verify(mapper).insert(model);
    }

    @Test
    void createDoesNotReplaceExistingDefault() {
        when(mapper.selectCount(any())).thenReturn(1L);

        ModelDefinition model = service.create(input("sk-secret", false));

        assertThat(model.isDefaultModel()).isFalse();
    }

    @Test
    void updateKeepsExistingApiKeyWhenNotProvided() {
        ModelDefinition existing = existingModel();
        String ciphertext = existing.getApiKeyCiphertext();
        when(mapper.selectById(MODEL_ID)).thenReturn(existing);

        ModelDefinition updated = service.update(MODEL_ID, new ModelDefinitionInput(
                "Renamed", null, null, null, null, null, null, null, null));

        assertThat(updated.getDisplayName()).isEqualTo("Renamed");
        assertThat(updated.getApiKeyCiphertext()).isEqualTo(ciphertext);
        assertThat(cipher.decrypt(updated.getApiKeyCiphertext())).isEqualTo("sk-old");
    }

    @Test
    void updateRejectsBlankApiKey() {
        when(mapper.selectById(MODEL_ID)).thenReturn(existingModel());

        assertThatThrownBy(() -> service.update(MODEL_ID, new ModelDefinitionInput(
                null, null, " ", null, null, null, null, null, null)))
                .satisfies(exception -> assertError(exception, AgentErrorCode.MODEL_API_KEY_REQUIRED));
    }

    @Test
    void disabledModelCannotBeDefault() {
        ModelDefinition existing = existingModel();
        existing.setEnabled(false);
        when(mapper.selectById(MODEL_ID)).thenReturn(existing);

        assertThatThrownBy(() -> service.setDefault(MODEL_ID))
                .satisfies(exception -> assertError(exception, AgentErrorCode.MODEL_CONFIG_INVALID));
    }

    @Test
    void requiresConfiguredModel() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(service::requireDefaultEnabled)
                .satisfies(exception -> assertError(exception, AgentErrorCode.MODEL_NOT_CONFIGURED));
    }

    private ModelDefinitionInput input(String apiKey, boolean defaultModel) {
        return new ModelDefinitionInput(
                "Mock Text",
                "https://api.example.com/v1",
                apiKey,
                "mock-model",
                ReasoningEffort.MEDIUM,
                8_000,
                1_000,
                true,
                defaultModel);
    }

    private ModelDefinition existingModel() {
        ModelDefinition model = new ModelDefinition();
        model.setId(MODEL_ID);
        model.setDisplayName("Mock Text");
        model.setBaseUrl("https://api.example.com/v1");
        model.setApiKeyCiphertext(cipher.encrypt("sk-old"));
        model.setModelId("mock-model");
        model.setReasoningEffort(ReasoningEffort.MEDIUM);
        model.setMaxInputTokens(8_000);
        model.setMaxOutputTokens(1_000);
        model.setEnabled(true);
        model.setDefaultModel(true);
        model.setCreatedAt(NOW);
        model.setUpdatedAt(NOW);
        return model;
    }

    private void assertError(Throwable exception, AgentErrorCode errorCode) {
        assertThat(exception).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(errorCode);
    }
}
