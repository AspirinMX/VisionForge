package app.pagefault.visionforge.agent.provider.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import app.pagefault.visionforge.agent.generation.GenerationInputRole;
import app.pagefault.visionforge.agent.generation.GenerationType;
import app.pagefault.visionforge.agent.model.ExecutionMode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ArkVideoProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsVideoTaskRequestWithInputsAndOptions() {
        ArkVideoProvider provider = new ArkVideoProvider(objectMapper, new ArkClientFactory());
        MediaProviderRequest req = new MediaProviderRequest(
                UUID.randomUUID(),
                GenerationType.VIDEO,
                "http://127.0.0.1:8000",
                "mock-key",
                "mock-seedance",
                "camera pan over coffee shop",
                """
                {"resolution":"720p","ratio":"16:9","durationSeconds":5,"generateAudio":true,"watermark":false,"seed":42}
                """,
                ExecutionMode.REMOTE_TASK,
                List.of(
                        new MediaProviderInput(
                                UUID.randomUUID(),
                                GenerationInputRole.FIRST_FRAME,
                                "image/png",
                                "https://media.example.com/first.png",
                                null)));

        JsonNode built = provider.createRequest(req);

        assertThat(built.path("model").asText()).isEqualTo("mock-seedance");
        assertThat(built.path("resolution").asText()).isEqualTo("720p");
        assertThat(built.path("ratio").asText()).isEqualTo("16:9");
        assertThat(built.path("duration").asLong()).isEqualTo(5L);
        assertThat(built.path("generate_audio").asBoolean()).isTrue();
        assertThat(built.path("watermark").asBoolean()).isFalse();
        assertThat(built.path("seed").asLong()).isEqualTo(42L);
        assertThat(built.path("content").size()).isEqualTo(2);
        assertThat(built.path("content").get(0).path("type").asText()).isEqualTo("text");
        assertThat(built.path("content").get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(built.path("content").get(1).path("image_url").path("url").asText())
                .isEqualTo("https://media.example.com/first.png");
        assertThat(built.path("content").get(1).path("role").asText()).isEqualTo("first_frame");
    }

    @Test
    void submitsPollsAndDownloadsVideoArtifactOverHttp() throws IOException {
        byte[] sampleMp4 = new byte[] {0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p'};
        AtomicInteger polls = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int[] portHolder = new int[1];

        server.createContext("/api/v3/contents/generations/tasks", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] responseBytes;
            if ("POST".equals(exchange.getRequestMethod())
                    && "/api/v3/contents/generations/tasks".equals(path)) {
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                responseBytes = """
                        {"id":"cgt-12345","model":"mock-seedance"}
                        """.getBytes(StandardCharsets.UTF_8);
            } else if ("GET".equals(exchange.getRequestMethod())
                    && "/api/v3/contents/generations/tasks/cgt-12345".equals(path)) {
                int count = polls.incrementAndGet();
                if (count == 1) {
                    responseBytes = """
                            {"id":"cgt-12345","status":"running"}
                            """.getBytes(StandardCharsets.UTF_8);
                } else {
                    responseBytes = ("""
                            {"id":"cgt-12345","status":"succeeded","content":{"video_url":"http://127.0.0.1:%d/media/mock.mp4"}}
                            """.formatted(portHolder[0])).getBytes(StandardCharsets.UTF_8);
                }
            } else {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(responseBytes);
            }
        });
        server.createContext("/media/mock.mp4", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, sampleMp4.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(sampleMp4);
            }
        });
        server.start();
        portHolder[0] = server.getAddress().getPort();

        try {
            ArkVideoProvider provider = new ArkVideoProvider(
                    objectMapper,
                    new ArkClientFactory("VisionForge-Test/1.0"),
                    new HttpMediaDownloader(1024L * 1024, true, ""));

            MediaProviderRequest req = new MediaProviderRequest(
                    UUID.randomUUID(),
                    GenerationType.VIDEO,
                    "http://127.0.0.1:" + portHolder[0],
                    "mock-key",
                    "mock-seedance",
                    "slow motion rain",
                    "{}",
                    ExecutionMode.REMOTE_TASK,
                    List.of());

            MediaProviderOutcome started = provider.start(req);
            assertThat(started).isInstanceOf(MediaProviderOutcome.Pending.class);
            String remoteId = ((MediaProviderOutcome.Pending) started).remoteTaskId();
            assertThat(remoteId).isEqualTo("cgt-12345");
            assertThat(requestBody.get()).contains("slow motion rain");

            MediaProviderOutcome firstPoll = provider.query(req, remoteId);
            assertThat(firstPoll).isInstanceOf(MediaProviderOutcome.Pending.class);

            MediaProviderOutcome secondPoll = provider.query(req, remoteId);
            assertThat(secondPoll).isInstanceOf(MediaProviderOutcome.Completed.class);
            var completed = (MediaProviderOutcome.Completed) secondPoll;
            assertThat(completed.artifacts()).hasSize(1);
            assertThat(completed.artifacts().get(0).mimeType()).isEqualTo("video/mp4");
            assertThat(completed.artifacts().get(0).content()).isEqualTo(sampleMp4);
        } finally {
            server.stop(0);
        }
    }
}
