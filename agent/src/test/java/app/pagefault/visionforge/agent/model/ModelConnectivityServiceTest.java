package app.pagefault.visionforge.agent.model;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import app.pagefault.visionforge.agent.provider.ModelResponse;
import app.pagefault.visionforge.agent.provider.ResponsesModelClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelConnectivityServiceTest {

    private static final UUID MODEL_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-000000000101");

    private ModelDefinitionService modelDefinitionService;
    private ResponsesModelClient responsesModelClient;
    private HttpClient httpClient;
    private ModelConnectivityService service;

    @BeforeEach
    void setUp() {
        modelDefinitionService = mock(ModelDefinitionService.class);
        responsesModelClient = mock(ResponsesModelClient.class);
        httpClient = mock(HttpClient.class);
        service = new ModelConnectivityService(modelDefinitionService, responsesModelClient, httpClient, "VisionForgeTest/1.0");
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsOkOnHttp200AndMasksSecretsOnFailure() throws Exception {
        ModelDefinition chatModel = chatModel();
        when(modelDefinitionService.get(MODEL_ID)).thenReturn(chatModel);
        when(modelDefinitionService.settings(chatModel)).thenReturn(
                new ModelProviderSettings(MODEL_ID, "https://api.example.com/v1", "sk-secret-value-123", "gpt-5", ReasoningEffort.MEDIUM, 128_000, 8_192));

        HttpResponse<Object> okResponse = mock(HttpResponse.class);
        when(okResponse.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any())).thenReturn(okResponse);

        ModelConnectivityService.ProbeResult ok = service.testConnection(MODEL_ID);
        assertThat(ok.ok()).isTrue();
        assertThat(ok.reachable()).isTrue();
        assertThat(ok.statusCode()).isEqualTo(200);

        HttpResponse<Object> unauthResponse = mock(HttpResponse.class);
        when(unauthResponse.statusCode()).thenReturn(401);
        when(httpClient.send(any(HttpRequest.class), any())).thenReturn(unauthResponse);

        ModelConnectivityService.ProbeResult unauthorized = service.testConnection(MODEL_ID);
        assertThat(unauthorized.ok()).isFalse();
        assertThat(unauthorized.reachable()).isTrue();
        assertThat(unauthorized.statusCode()).isEqualTo(401);

        when(httpClient.send(any(HttpRequest.class), any()))
                .thenThrow(new IOException("Connect failed using sk-secret-value-123"));
        ModelConnectivityService.ProbeResult unreachable = service.testConnection(MODEL_ID);
        assertThat(unreachable.ok()).isFalse();
        assertThat(unreachable.reachable()).isFalse();
        assertThat(unreachable.detail()).doesNotContain("sk-secret-value-123").contains("***");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallsBackToResponsesProbeWhenChatGatewayLacksModelsEndpoint() throws Exception {
        ModelDefinition chatModel = chatModel();
        when(modelDefinitionService.get(MODEL_ID)).thenReturn(chatModel);
        when(modelDefinitionService.settings(chatModel)).thenReturn(
                new ModelProviderSettings(MODEL_ID, "https://proxy.example.com/v1", "sk-test", "gpt-5", ReasoningEffort.LOW, 64_000, 4_096));

        HttpResponse<Object> notFound = mock(HttpResponse.class);
        when(notFound.statusCode()).thenReturn(404);
        when(httpClient.send(any(HttpRequest.class), any())).thenReturn(notFound);
        when(responsesModelClient.stream(any(), any())).thenReturn(Mockito.mock(ModelResponse.class));

        ModelConnectivityService.ProbeResult result = service.testConnection(MODEL_ID);
        assertThat(result.ok()).isTrue();
        assertThat(result.reachable()).isTrue();
        assertThat(result.detail()).contains("Responses");
    }

    private ModelDefinition chatModel() {
        ModelDefinition model = new ModelDefinition();
        model.setId(MODEL_ID);
        model.setCapabilityType(ModelCapabilityType.CHAT);
        model.setProtocol(MediaProtocol.OPENAI_RESPONSES);
        return model;
    }
}
