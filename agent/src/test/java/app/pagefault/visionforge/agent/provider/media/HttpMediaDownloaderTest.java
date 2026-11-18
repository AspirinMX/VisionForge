package app.pagefault.visionforge.agent.provider.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import app.pagefault.visionforge.common.error.BusinessException;

class HttpMediaDownloaderTest {

    @Test
    void rejectsHttpWhenTheDevelopmentSwitchIsOff() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/media", exchange -> {
            byte[] payload = "png".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        server.start();
        try {
            HttpMediaDownloader downloader = new HttpMediaDownloader(1024, false);
            assertThatThrownBy(() -> downloader.download(url(server)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("HTTPS");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadsHttpWhenDevelopmentSwitchIsEnabled() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/media", exchange -> {
            byte[] payload = "mock-video".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        server.start();
        try {
            HttpMediaDownloader downloader = new HttpMediaDownloader(1024, true);
            assertThat(downloader.download(url(server)))
                    .isEqualTo("mock-video".getBytes(StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    private String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/media/out.png";
    }
}
