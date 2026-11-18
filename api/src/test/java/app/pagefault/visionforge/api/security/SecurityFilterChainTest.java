package app.pagefault.visionforge.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.common.api.ApiResponse;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityFilterChainTest.ProbeController.class)
@Import({SecurityConfiguration.class, SecurityFilterChainTest.ProbeController.class})
@TestPropertySource(properties = {
        "visionforge.security.cors.allowed-origins=https://studio.example.com",
        "visionforge.security.rate-limit.auth-per-minute=1"
})
class SecurityFilterChainTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void enforcesCorsAllowlistAndSecurityHeadersAndRateLimitsAuth() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, "https://studio.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://studio.example.com"));

        mockMvc.perform(options("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.net")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/login"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Permissions-Policy", "camera=(), microphone=(), geolocation=()"));

        mockMvc.perform(post("/api/v1/auth/login"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("COMMON_RATE_LIMITED"));
    }

    @RestController
    static class ProbeController {

        @PostMapping("/api/v1/auth/login")
        public ApiResponse<Void> probeLogin() {
            return ApiResponse.success();
        }
    }
}
