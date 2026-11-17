package app.pagefault.visionforge.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import app.pagefault.visionforge.common.error.CommonErrorCode;
import app.pagefault.visionforge.common.logging.TraceContext;

class ApiResponseTest {

    @AfterEach
    void clearMdc() {
        TraceContext.clear();
    }

    @Test
    void successUsesOkCodeAndCurrentTraceId() {
        TraceContext.setTraceId("trace-12345");

        ApiResponse<String> response = ApiResponse.success("payload");

        assertThat(response.code()).isEqualTo("OK");
        assertThat(response.message()).isEqualTo("成功");
        assertThat(response.data()).isEqualTo("payload");
        assertThat(response.traceId()).isEqualTo("trace-12345");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    void errorUsesErrorCodeAndMachineReadableFailure() {
        TraceContext.setTraceId("trace-67890");

        ApiResponse<String> response = ApiResponse.error(CommonErrorCode.CONFLICT);

        assertThat(response.code()).isEqualTo("COMMON_CONFLICT");
        assertThat(response.message()).isEqualTo("请求与当前资源状态冲突");
        assertThat(response.data()).isNull();
        assertThat(response.traceId()).isEqualTo("trace-67890");
    }

    @Test
    void errorCanCarryDetails() {
        ApiResponse<String> response =
                ApiResponse.error(CommonErrorCode.INVALID_ARGUMENT, "参数错误", "field:email");

        assertThat(response.code()).isEqualTo("COMMON_INVALID_ARGUMENT");
        assertThat(response.data()).isEqualTo("field:email");
    }
}
