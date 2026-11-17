package app.pagefault.visionforge.agent.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

class OpenAiCompatibleChatModelClientTest {

    @Test
    void streamsDeltasAndAccumulatesContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = ("""
                    data: {"choices":[{"delta":{"content":"Hello "}}]}

                    data: {"choices":[{"delta":{"content":"world"}}]}

                    data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2}}

                    data: [DONE]

                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            OpenAiCompatibleChatModelClient client =
                    new OpenAiCompatibleChatModelClient(new ObjectMapper());
            List<String> deltas = new ArrayList<>();

            ChatCompletionResult result = client.stream(
                    new ChatCompletionRequest(
                            baseUrl,
                            "test-key",
                            "test-model",
                            null,
                            100,
                            List.of(new ChatTurn("user", "hi"))),
                    deltas::add);

            assertThat(deltas).containsExactly("Hello ", "world");
            assertThat(result.content()).isEqualTo("Hello world");
            assertThat(result.inputTokens()).isEqualTo(3);
            assertThat(result.outputTokens()).isEqualTo(2);
            assertThat(result.finishReason()).isEqualTo("stop");
        } finally {
            server.stop(0);
        }
    }
}
