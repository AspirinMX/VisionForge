package app.pagefault.visionforge.agent.provider.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import app.pagefault.visionforge.agent.generation.GenerationInputRole;
import app.pagefault.visionforge.agent.generation.GenerationType;
import app.pagefault.visionforge.agent.model.ExecutionMode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DashScopeProvidersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void t2iSubmitsAsyncTaskAndArchivesResult() throws Exception {
        AtomicReference<String> submitBody = new AtomicReference<>();
        AtomicReference<String> asyncHeader = new AtomicReference<>();
        AtomicReference<String> capturedUserAgent = new AtomicReference<>();
        AtomicReference<String> capturedUrl = new AtomicReference<>();
        byte[] image = new byte[]{1, 2, 3};

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/services/aigc/text2image/image-synthesis", exchange -> {
            submitBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            asyncHeader.set(exchange.getRequestHeaders().getFirst("X-DashScope-Async"));
            capturedUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            respond(exchange, 200, """
                    {"request_id":"req-submit","output":{"task_id":"task-t2i","task_status":"PENDING"}}
                    """);
        });
        server.createContext("/api/v1/tasks/task-t2i", exchange -> respond(exchange, 200, """
                {"request_id":"req-query","output":{"task_id":"task-t2i","task_status":"SUCCEEDED",
                 "results":[{"url":"https://cdn.example.com/out.png"}]}}
                """));
        server.start();
        try {
            DashScopeT2iProvider provider = new DashScopeT2iProvider(objectMapper, url -> {
                capturedUrl.set(url);
                return image;
            }, "VisionForge-Test/1.0");
            MediaProviderRequest request = request(
                    GenerationType.IMAGE,
                    baseUrl(server),
                    "{\"size\":\"1024*1024\",\"n\":1,\"promptExtend\":true}",
                    List.of());

            MediaProviderOutcome started = provider.start(request);
            assertThat(started).isInstanceOf(MediaProviderOutcome.Pending.class);
            assertThat(((MediaProviderOutcome.Pending) started).remoteTaskId())
                    .isEqualTo("task-t2i");
            assertThat(asyncHeader.get()).isEqualTo("enable");
            assertThat(capturedUserAgent.get()).isEqualTo("VisionForge-Test/1.0");

            JsonNode submitted = objectMapper.readTree(submitBody.get());
            assertThat(submitted.path("model").asText()).isEqualTo("wan2.7-image");
            assertThat(submitted.path("input").path("prompt").asText()).isEqualTo("draw a cat");
            assertThat(submitted.path("parameters").path("size").asText())
                    .isEqualTo("1024*1024");
            assertThat(submitted.path("parameters").path("prompt_extend").asBoolean())
                    .isTrue();

            MediaProviderOutcome queried = provider.query(request, "task-t2i");
            assertThat(queried).isInstanceOf(MediaProviderOutcome.Completed.class);
            MediaProviderOutcome.Completed completed = (MediaProviderOutcome.Completed) queried;
            assertThat(completed.artifacts()).hasSize(1);
            assertThat(completed.artifacts().get(0).mimeType()).isEqualTo("image/png");
            assertThat(completed.artifacts().get(0).content()).isEqualTo(image);
            assertThat(capturedUrl.get()).isEqualTo("https://cdn.example.com/out.png");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void videoMapsMediaRolesAndParameters() throws Exception {
        AtomicReference<String> submitBody = new AtomicReference<>();
        AtomicReference<String> capturedUrl = new AtomicReference<>();
        byte[] video = new byte[]{9, 8, 7};

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/services/aigc/video-generation/video-synthesis", exchange -> {
            submitBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"request_id":"req-video","output":{"task_id":"task-video","task_status":"PENDING"}}
                    """);
        });
        server.createContext("/api/v1/tasks/task-video", exchange -> respond(exchange, 200, """
                {"output":{"task_id":"task-video","task_status":"SUCCEEDED",
                 "video_url":"https://cdn.example.com/out.mp4"}}
                """));
        server.start();
        try {
            DashScopeVideoProvider provider = new DashScopeVideoProvider(objectMapper, url -> {
                capturedUrl.set(url);
                return video;
            });
            MediaProviderRequest request = request(
                    GenerationType.VIDEO,
                    baseUrl(server),
                    "{\"resolution\":\"720p\",\"aspectRatio\":\"9:16\",\"durationSeconds\":5,\"generateAudio\":true}",
                    List.of(new MediaProviderInput(
                            UUID.randomUUID(),
                            GenerationInputRole.FIRST_FRAME,
                            "image/png",
                            "https://media.example.com/first.png",
                            null)));

            provider.start(request);
            JsonNode submitted = objectMapper.readTree(submitBody.get());
            assertThat(submitted.path("input").path("media").get(0).path("type").asText())
                    .isEqualTo("first_frame");
            assertThat(submitted.path("input").path("media").get(0).path("url").asText())
                    .isEqualTo("https://media.example.com/first.png");
            assertThat(submitted.path("parameters").path("resolution").asText())
                    .isEqualTo("720P");
            assertThat(submitted.path("parameters").path("ratio").asText())
                    .isEqualTo("9:16");
            assertThat(submitted.path("parameters").path("duration").asInt()).isEqualTo(5);
            assertThat(submitted.path("parameters").path("audio").asBoolean()).isTrue();

            MediaProviderOutcome queried = provider.query(request, "task-video");
            assertThat(queried).isInstanceOf(MediaProviderOutcome.Completed.class);
            MediaProviderOutcome.Completed completed = (MediaProviderOutcome.Completed) queried;
            assertThat(completed.artifacts().get(0).mimeType()).isEqualTo("video/mp4");
            assertThat(capturedUrl.get()).isEqualTo("https://cdn.example.com/out.mp4");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void imageMessagesBuildsMultimodalContentAndDownloadsImage() throws Exception {
        AtomicReference<String> submitBody = new AtomicReference<>();
        byte[] image = new byte[]{4, 5};

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/services/aigc/multimodal-generation/generation", exchange -> {
            submitBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"request_id":"req-msg","output":{"choices":[{"message":{"content":[
                      {"image":"https://cdn.example.com/message.png"}]}}]}}
                    """);
        });
        server.start();
        try {
            DashScopeImageMessagesProvider provider =
                    new DashScopeImageMessagesProvider(objectMapper, url -> image);
            MediaProviderRequest request = request(
                    GenerationType.IMAGE,
                    baseUrl(server),
                    "{\"size\":\"2K\",\"n\":1}",
                    List.of(
                            new MediaProviderInput(
                                    UUID.randomUUID(),
                                    GenerationInputRole.REFERENCE,
                                    "image/png",
                                    "https://media.example.com/ref.png",
                                    null),
                            new MediaProviderInput(
                                    UUID.randomUUID(),
                                    GenerationInputRole.REFERENCE,
                                    "image/png",
                                    "http://localhost:9000/private-ref.png",
                                    new byte[]{1, 2, 3})));

            MediaProviderOutcome outcome = provider.start(request);
            assertThat(outcome).isInstanceOf(MediaProviderOutcome.Completed.class);
            assertThat(((MediaProviderOutcome.Completed) outcome).artifacts().get(0).content())
                    .isEqualTo(image);

            JsonNode submitted = objectMapper.readTree(submitBody.get());
            JsonNode content = submitted.path("input").path("messages").get(0).path("content");
            assertThat(content.get(0).path("text").asText()).isEqualTo("draw a cat");
            assertThat(content.get(1).path("image").asText())
                    .isEqualTo("https://media.example.com/ref.png");
            assertThat(content.get(2).path("image").asText())
                    .isEqualTo("data:image/png;base64,AQID");
        } finally {
            server.stop(0);
        }
    }

    private MediaProviderRequest request(
            GenerationType type,
            String baseUrl,
            String parameters,
            List<MediaProviderInput> inputs) {
        return new MediaProviderRequest(
                UUID.randomUUID(),
                type,
                baseUrl,
                "test-key",
                "wan2.7-image",
                "draw a cat",
                parameters,
                ExecutionMode.REMOTE_TASK,
                inputs);
    }

    private String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
    }

    private void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }
}
