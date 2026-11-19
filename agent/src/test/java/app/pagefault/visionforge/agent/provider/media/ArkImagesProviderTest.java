package app.pagefault.visionforge.agent.provider.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import app.pagefault.visionforge.agent.generation.GenerationInputRole;
import app.pagefault.visionforge.agent.generation.GenerationType;
import app.pagefault.visionforge.agent.model.ExecutionMode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ArkImagesProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ArkClientFactory arkClients = new ArkClientFactory();
    private final ArkImagesProvider provider =
            new ArkImagesProvider(objectMapper, arkClients);

    @Test
    void exposesConfiguredUserAgentAsCustomHeader() {
        assertThat(new ArkClientFactory("VisionForge-Test/1.0").customHeaders())
                .containsEntry("User-Agent", "VisionForge-Test/1.0");
        assertThat(new ArkClientFactory().customHeaders()).isEmpty();
    }

    @Test
    void mapsSeedreamRequestWithReferenceImages() {
        MediaProviderRequest request = new MediaProviderRequest(
                UUID.randomUUID(),
                GenerationType.IMAGE,
                "https://ark.example.com/api/v3",
                "test-key",
                "doubao-seedream-4-0",
                "a cat on a windowsill",
                """
                {"size":"2048x2048","outputFormat":"jpeg","watermark":false,"seed":7}
                """,
                ExecutionMode.INLINE_RESULT,
                List.of(
                        new MediaProviderInput(
                                UUID.randomUUID(),
                                GenerationInputRole.REFERENCE,
                                "image/png",
                                "https://media.example.com/ref-1.png",
                                null),
                        new MediaProviderInput(
                                UUID.randomUUID(),
                                GenerationInputRole.INIT_IMAGE,
                                "image/png",
                                "https://media.example.com/ref-2.png",
                                null)));

        JsonNode built = provider.createRequest(request);

        assertThat(built.path("model").asText()).isEqualTo("doubao-seedream-4-0");
        assertThat(built.path("prompt").asText()).isEqualTo("a cat on a windowsill");
        assertThat(built.path("size").asText()).isEqualTo("2048x2048");
        assertThat(built.path("output_format").asText()).isEqualTo("jpeg");
        assertThat(built.path("response_format").asText()).isEqualTo("b64_json");
        assertThat(built.path("watermark").asBoolean()).isFalse();
        assertThat(built.path("seed").asLong()).isEqualTo(7L);
        assertThat(built.path("image").size()).isEqualTo(2);
        assertThat(built.path("image").get(0).asText())
                .isEqualTo("https://media.example.com/ref-1.png");
        assertThat(built.path("image").get(1).asText())
                .isEqualTo("https://media.example.com/ref-2.png");
    }

    @Test
    void generatesImageOverHttpWithoutDoubleApiPrefix() throws IOException {
        byte[] samplePng = new byte[] {(byte) 0x89, 'P', 'N', 'G'};
        AtomicReference<String> seenPath = new AtomicReference<>();
        AtomicReference<String> seenAuth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/images/generations", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] responseBody = ("""
                    {"created":1,"data":[{"b64_json":"%s","output_format":"png"}],"usage":{"generated_images":1,"total_tokens":100}}
                    """.formatted(Base64.getEncoder().encodeToString(samplePng)))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(responseBody);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            MediaProviderRequest req = new MediaProviderRequest(
                    UUID.randomUUID(),
                    GenerationType.IMAGE,
                    "http://127.0.0.1:" + port + "/api/v3",
                    "mock-key",
                    "doubao-seedream",
                    "sunset",
                    "{}",
                    ExecutionMode.INLINE_RESULT,
                    List.of());

            MediaProviderOutcome outcome = provider.start(req);

            assertThat(seenPath.get()).isEqualTo("/api/v3/images/generations");
            assertThat(seenAuth.get()).isEqualTo("Bearer mock-key");
            assertThat(outcome).isInstanceOf(MediaProviderOutcome.Completed.class);
            var completed = (MediaProviderOutcome.Completed) outcome;
            assertThat(completed.artifacts()).hasSize(1);
            assertThat(completed.artifacts().get(0).content()).isEqualTo(samplePng);
        } finally {
            server.stop(0);
        }
    }
}
