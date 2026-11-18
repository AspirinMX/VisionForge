package app.pagefault.visionforge.agent.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import app.pagefault.visionforge.agent.model.ReasoningEffort;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies that the request handed to the SDK is a Responses API request: {@code POST /responses}
 * with an explicit input array and Responses-style function tools. The upstream answer is
 * irrelevant here, so the stub server fails the call once the body has been captured.
 */
class OpenAiResponsesModelClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsExplicitResponsesInputAndFunctionTools() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedPath = new AtomicReference<>();
        AtomicReference<String> capturedUserAgent = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            capturedBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = "{\"error\":{\"message\":\"stub\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, payload.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        server.start();
        try {
            OpenAiResponsesModelClient client =
                    new OpenAiResponsesModelClient("VisionForge-Test/1.0");
            ModelRequest request = new ModelRequest(
                    baseUrl(server),
                    "test-key",
                    "test-model",
                    ReasoningEffort.LOW,
                    128,
                    "system instructions",
                    List.of(
                            new ModelInputItem.Message("user", "hello"),
                            new ModelInputItem.Reasoning("gAAAA-encrypted", null, List.of("plan")),
                            new ModelInputItem.FunctionCall("call_1", "read_item", "{\"a\":1}"),
                            new ModelInputItem.FunctionCallOutput("call_1", "{\"ok\":true}")),
                    List.of(new ModelToolSpec(
                            "read_item", "read an item", toolSchema())),
                    "vf-chat:test-conversation");

            assertThatThrownBy(() -> client.stream(request, ModelStreamListener.NOOP))
                    .isInstanceOf(ModelProviderException.class);

            assertThat(capturedPath.get()).isEqualTo("/v1/responses");
            assertThat(capturedUserAgent.get()).isEqualTo("VisionForge-Test/1.0");
            JsonNode body = objectMapper.readTree(capturedBody.get());
            assertThat(body.path("model").asText()).isEqualTo("test-model");
            assertThat(body.path("store").asBoolean()).isFalse();
            assertThat(body.path("stream").asBoolean()).isTrue();
            assertThat(body.path("max_output_tokens").asInt()).isEqualTo(128);
            assertThat(body.path("instructions").asText()).isEqualTo("system instructions");
            assertThat(body.path("reasoning").path("effort").asText()).isEqualTo("low");
            assertThat(body.path("prompt_cache_key").asText())
                    .isEqualTo("vf-chat:test-conversation");

            assertThat(body.path("include").get(0).asText())
                    .isEqualTo("reasoning.encrypted_content");

            JsonNode input = body.path("input");
            assertThat(input).hasSize(4);
            assertThat(input.get(0).path("role").asText()).isEqualTo("user");
            assertThat(input.get(0).path("content").asText()).isEqualTo("hello");

            JsonNode reasoning = input.get(1);
            assertThat(reasoning.path("type").asText()).isEqualTo("reasoning");
            assertThat(reasoning.path("encrypted_content").asText())
                    .isEqualTo("gAAAA-encrypted");
            assertThat(reasoning.path("summary").get(0).path("text").asText())
                    .isEqualTo("plan");
            assertThat(reasoning.has("id")).isFalse();

            assertThat(input.get(2).path("type").asText()).isEqualTo("function_call");
            assertThat(input.get(2).path("call_id").asText()).isEqualTo("call_1");
            assertThat(input.get(2).path("name").asText()).isEqualTo("read_item");
            assertThat(input.get(2).path("arguments").asText()).isEqualTo("{\"a\":1}");
            assertThat(input.get(3).path("type").asText()).isEqualTo("function_call_output");
            assertThat(input.get(3).path("call_id").asText()).isEqualTo("call_1");
            assertThat(input.get(3).path("output").asText()).isEqualTo("{\"ok\":true}");

            JsonNode tool = body.path("tools").get(0);
            assertThat(tool.path("type").asText()).isEqualTo("function");
            assertThat(tool.path("name").asText()).isEqualTo("read_item");
            assertThat(tool.path("parameters").path("type").asText()).isEqualTo("object");
            assertThat(tool.path("parameters").path("required").get(0).asText())
                    .isEqualTo("itemId");
            assertThat(body.has("messages")).isFalse();
        } finally {
            server.stop(0);
        }
    }

    private String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private JsonNode toolSchema() {
        return objectMapper.readTree("""
                {"type":"object","properties":{"itemId":{"type":"string","format":"uuid"}},
                 "required":["itemId"],"additionalProperties":false}
                """);
    }
}
