package app.pagefault.visionforge.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TraceContextTest {

    @AfterEach
    void clearMdc() {
        TraceContext.clear();
    }

    @Test
    void generatedTraceIdIsValid() {
        String traceId = TraceContext.generateTraceId();

        assertThat(traceId).hasSize(32);
        assertThat(TraceContext.isValidTraceId(traceId)).isTrue();
    }

    @Test
    void keepsValidIncomingTraceId() {
        String traceId = TraceContext.ensureTraceId("abc-123_xyz");

        assertThat(traceId).isEqualTo("abc-123_xyz");
        assertThat(TraceContext.currentTraceId()).isEqualTo("abc-123_xyz");
    }

    @Test
    void replacesInvalidIncomingTraceId() {
        String traceId = TraceContext.ensureTraceId("invalid trace id!");

        assertThat(traceId).isNotEqualTo("invalid trace id!");
        assertThat(TraceContext.isValidTraceId(traceId)).isTrue();
        assertThat(TraceContext.currentTraceId()).isEqualTo(traceId);
    }

    @Test
    void clearsTraceId() {
        TraceContext.setTraceId("trace-12345");
        TraceContext.clear();

        assertThat(TraceContext.currentTraceId()).isNull();
    }
}
