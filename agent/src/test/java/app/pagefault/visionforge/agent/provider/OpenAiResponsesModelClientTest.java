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
            assertThat(body.path("reasoning").path("summary").asText()).isEqualTo("auto");
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

    @Test
    void streamsMultiPartReasoningSummaryAndRecoversMissingCompletedSummary() throws Exception {
        String sse = """
                event: response.reasoning_summary_part.added
                data: {"type":"response.reasoning_summary_part.added","item_id":"rs_1","output_index":0,"summary_index":0,"sequence_number":1,"part":{"type":"summary_text","text":""}}

                event: response.reasoning_summary_text.delta
                data: {"type":"response.reasoning_summary_text.delta","item_id":"rs_1","output_index":0,"summary_index":0,"sequence_number":2,"delta":"Analyzing request"}

                event: response.reasoning_summary_part.added
                data: {"type":"response.reasoning_summary_part.added","item_id":"rs_1","output_index":0,"summary_index":1,"sequence_number":3,"part":{"type":"summary_text","text":""}}

                event: response.reasoning_summary_text.delta
                data: {"type":"response.reasoning_summary_text.delta","item_id":"rs_1","output_index":0,"summary_index":1,"sequence_number":4,"delta":"Drafting answer"}

                event: response.output_text.delta
                data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":1,"content_index":0,"sequence_number":5,"delta":"Final answer","logprobs":[]}

                event: response.completed
                data: {"type":"response.completed","sequence_number":6,"response":{"id":"resp_1","object":"response","created_at":1,"status":"completed","error":null,"incomplete_details":null,"instructions":null,"metadata":{},"model":"test-model","parallel_tool_calls":true,"temperature":1.0,"tool_choice":"auto","tools":[],"top_p":1.0,"output":[{"id":"rs_1","type":"reasoning","encrypted_content":"enc_cipher","summary":[]},{"id":"msg_1","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Final answer","annotations":[],"logprobs":[]}]}]}}

                """;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] payload = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        server.start();
        try {
            OpenAiResponsesModelClient client = new OpenAiResponsesModelClient();
            StringBuilder summary = new StringBuilder();
            StringBuilder text = new StringBuilder();
            ModelResponse response = client.stream(
                    new ModelRequest(
                            baseUrl(server),
                            "test-key",
                            "test-model",
                            ReasoningEffort.MEDIUM,
                            128,
                            null,
                            List.of(new ModelInputItem.Message("user", "hello")),
                            List.of(),
                            null),
                    new ModelStreamListener() {
                        @Override
                        public void onTextDelta(String delta) {
                            text.append(delta);
                        }

                        @Override
                        public void onReasoningSummaryDelta(String delta) {
                            summary.append(delta);
                        }
                    });

            assertThat(summary.toString()).isEqualTo("Analyzing request\nDrafting answer");
            assertThat(text.toString()).isEqualTo("Final answer");
            assertThat(response.reasoning()).hasSize(1);
            assertThat(response.reasoning().get(0).encryptedContent()).isEqualTo("enc_cipher");
            assertThat(response.reasoning().get(0).summary())
                    .containsExactly("Analyzing request", "Drafting answer");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void capturesPlaintextReasoningTextFromStreamDeltas() throws Exception {
        String sse = """
                event: response.output_item.added
                data: {"type":"response.output_item.added","output_index":0,"item":{"id":"rs_1","type":"reasoning","summary":[]}}

                event: response.reasoning_text.delta
                data: {"type":"response.reasoning_text.delta","item_id":"rs_1","output_index":0,"content_index":0,"sequence_number":2,"delta":"step one "}

                event: response.reasoning_text.delta
                data: {"type":"response.reasoning_text.delta","item_id":"rs_1","output_index":0,"content_index":0,"sequence_number":3,"delta":"step two"}

                event: response.output_text.delta
                data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":1,"content_index":0,"sequence_number":4,"delta":"Done","logprobs":[]}

                event: response.completed
                data: {"type":"response.completed","sequence_number":5,"response":{"id":"resp_r","object":"response","created_at":1,"status":"completed","error":null,"incomplete_details":null,"instructions":null,"metadata":{},"model":"test-model","parallel_tool_calls":true,"temperature":1.0,"tool_choice":"auto","tools":[],"top_p":1.0,"output":[{"id":"rs_1","type":"reasoning","summary":[]},{"id":"msg_1","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Done","annotations":[],"logprobs":[]}]}]}}

                """;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] payload = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        server.start();
        try {
            OpenAiResponsesModelClient client = new OpenAiResponsesModelClient();
            StringBuilder reasoningText = new StringBuilder();
            ModelResponse response = client.stream(
                    new ModelRequest(
                            baseUrl(server),
                            "test-key",
                            "deepseek-flash",
                            ReasoningEffort.HIGH,
                            128,
                            null,
                            List.of(new ModelInputItem.Message("user", "hello")),
                            List.of(),
                            null),
                    new ModelStreamListener() {
                        @Override
                        public void onReasoningDelta(String delta) {
                            reasoningText.append(delta);
                        }
                    });

            assertThat(reasoningText.toString()).isEqualTo("step one step two");
            assertThat(response.reasoning()).hasSize(1);
            assertThat(response.reasoning().get(0).text())
                    .isEqualTo("step one step two");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsPlaintextReasoningBackAsReasoningTextContent() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
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
            OpenAiResponsesModelClient client = new OpenAiResponsesModelClient();
            ModelRequest request = new ModelRequest(
                    baseUrl(server),
                    "test-key",
                    "deepseek-flash",
                    ReasoningEffort.HIGH,
                    128,
                    null,
                    List.of(new ModelInputItem.Reasoning(
                            "unusable-encrypted-carrier", "step one step two", List.of("plan"))),
                    List.of(),
                    null);

            assertThatThrownBy(() -> client.stream(request, ModelStreamListener.NOOP))
                    .isInstanceOf(ModelProviderException.class);

            JsonNode reasoning = objectMapper.readTree(capturedBody.get())
                    .path("input").get(0);
            assertThat(reasoning.path("type").asText()).isEqualTo("reasoning");
            assertThat(reasoning.path("content").get(0).path("type").asText())
                    .isEqualTo("reasoning_text");
            assertThat(reasoning.path("content").get(0).path("text").asText())
                    .isEqualTo("step one step two");
            assertThat(reasoning.path("summary")).isEmpty();
            assertThat(reasoning.has("encrypted_content")).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToSummaryTextDoneAndOmitsReasoningWhenEffortIsNone() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        String sse = """
                event: response.reasoning_summary_text.done
                data: {"type":"response.reasoning_summary_text.done","item_id":"rs_2","output_index":0,"summary_index":0,"sequence_number":1,"text":"Summary from done"}

                event: response.output_text.delta
                data: {"type":"response.output_text.delta","item_id":"msg_2","output_index":1,"content_index":0,"sequence_number":2,"delta":"OK","logprobs":[]}

                event: response.completed
                data: {"type":"response.completed","sequence_number":3,"response":{"id":"resp_2","object":"response","created_at":1,"status":"completed","error":null,"incomplete_details":null,"instructions":null,"metadata":{},"model":"test-model","parallel_tool_calls":true,"temperature":1.0,"tool_choice":"auto","tools":[],"top_p":1.0,"output":[{"id":"rs_2","type":"reasoning","encrypted_content":"enc_2","summary":[{"type":"summary_text","text":"Summary from done"}]},{"id":"msg_2","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"OK","annotations":[],"logprobs":[]}]}]}}

                """;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            capturedBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        server.start();
        try {
            OpenAiResponsesModelClient client = new OpenAiResponsesModelClient();
            StringBuilder summary = new StringBuilder();
            ModelResponse response = client.stream(
                    new ModelRequest(
                            baseUrl(server),
                            "test-key",
                            "test-model",
                            ReasoningEffort.NONE,
                            128,
                            null,
                            List.of(new ModelInputItem.Message("user", "ping")),
                            List.of(),
                            null),
                    new ModelStreamListener() {
                        @Override
                        public void onReasoningSummaryDelta(String delta) {
                            summary.append(delta);
                        }
                    });

            JsonNode body = objectMapper.readTree(capturedBody.get());
            assertThat(body.has("reasoning")).isFalse();
            assertThat(summary.toString()).isEqualTo("Summary from done");
            assertThat(response.reasoning().get(0).summary()).containsExactly("Summary from done");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsExtendedReasoningEffortsXhighAndMax() throws Exception {
        for (ReasoningEffort effort : List.of(ReasoningEffort.XHIGH, ReasoningEffort.MAX)) {
            AtomicReference<String> capturedBody = new AtomicReference<>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/responses", exchange -> {
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
                OpenAiResponsesModelClient client = new OpenAiResponsesModelClient();
                ModelRequest request = new ModelRequest(
                        baseUrl(server),
                        "test-key",
                        "test-model",
                        effort,
                        128,
                        null,
                        List.of(new ModelInputItem.Message("user", "hello")),
                        List.of(),
                        null);
                assertThatThrownBy(() -> client.stream(request, ModelStreamListener.NOOP))
                        .isInstanceOf(ModelProviderException.class);
                JsonNode body = objectMapper.readTree(capturedBody.get());
                assertThat(body.path("reasoning").path("effort").asText())
                        .isEqualTo(effort.name().toLowerCase(java.util.Locale.ROOT));
                assertThat(body.path("reasoning").path("summary").asText()).isEqualTo("auto");
            } finally {
                server.stop(0);
            }
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
