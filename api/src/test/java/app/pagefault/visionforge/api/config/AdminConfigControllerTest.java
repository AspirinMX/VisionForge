package app.pagefault.visionforge.api.config;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import app.pagefault.visionforge.api.security.SecurityConfiguration;
import app.pagefault.visionforge.common.config.DynamicRuntimeConfig;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminConfigController.class)
@Import(SecurityConfiguration.class)
class AdminConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private DynamicRuntimeConfig dynamicRuntimeConfig;

    @BeforeEach
    void stubJwt() {
        Instant now = Instant.parse("2026-11-19T00:00:00Z");
        when(jwtDecoder.decode("admin-jwt")).thenReturn(Jwt.withTokenValue("admin-jwt")
                .header("alg", "HS256")
                .subject("0193b2c4-7f2a-7c3e-9d10-000000000001")
                .claim("role", "ADMIN")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build());
        when(jwtDecoder.decode("user-jwt")).thenReturn(Jwt.withTokenValue("user-jwt")
                .header("alg", "HS256")
                .subject("0193b2c4-7f2a-7c3e-9d10-000000000002")
                .claim("role", "USER")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build());
    }

    @Test
    void enforcesAdminAccessAndPublishesAndResetsConfig() throws Exception {
        mockMvc.perform(get("/api/v1/admin/configs")
                        .header("Authorization", "Bearer user-jwt"))
                .andExpect(status().isForbidden());

        DynamicRuntimeConfig.ConfigEntry entry = new DynamicRuntimeConfig.ConfigEntry(
                "visionforge-billing.json",
                "VISIONFORGE_GROUP",
                "public",
                "billing",
                "{\"initialCreditUnits\":20000}",
                "{\"initialCreditUnits\":10000}",
                "abc123",
                "NACOS",
                true,
                true,
                Instant.parse("2026-11-19T00:00:00Z"));
        when(dynamicRuntimeConfig.statusSummary()).thenReturn(
                new DynamicRuntimeConfig.Status(true, true, "127.0.0.1:8848", "public", "VISIONFORGE_GROUP", "NACOS"));
        when(dynamicRuntimeConfig.listEntries()).thenReturn(List.of(entry));
        when(dynamicRuntimeConfig.publish("visionforge-billing.json", "{\"initialCreditUnits\":20000}"))
                .thenReturn(entry);
        when(dynamicRuntimeConfig.reset("visionforge-billing.json")).thenReturn(entry);

        mockMvc.perform(get("/api/v1/admin/configs")
                        .header("Authorization", "Bearer admin-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.entries[0].dataId").value("visionforge-billing.json"));

        mockMvc.perform(put("/api/v1/admin/configs/visionforge-billing.json")
                        .header("Authorization", "Bearer admin-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"{\\\"initialCreditUnits\\\":20000}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("NACOS"));

        mockMvc.perform(post("/api/v1/admin/configs/visionforge-billing.json/reset")
                        .header("Authorization", "Bearer admin-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataId").value("visionforge-billing.json"));
    }
}
