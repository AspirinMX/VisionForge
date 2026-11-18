package app.pagefault.visionforge.api.audit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import app.pagefault.visionforge.api.security.SecurityConfiguration;
import app.pagefault.visionforge.common.api.PageResult;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminAuditLogController.class)
@Import(SecurityConfiguration.class)
class AdminAuditLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private AuditLogQueryService auditLogQueryService;

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
    void rejectsNonAdminAndReturnsFilteredAuditLogsAndCsvForAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", "Bearer user-jwt"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        AuditLogEntity entity = new AuditLogEntity();
        entity.setId(UUID.fromString("0193b2c4-7f2a-7c3e-9d10-000000000020"));
        entity.setType("IDENTITY_USER");
        entity.setSubType("ADMIN_CREATE");
        entity.setSuccess(true);
        entity.setCreatedAt(Instant.parse("2026-11-19T00:00:00Z"));
        when(auditLogQueryService.query(eq(1L), eq(50L), eq("IDENTITY_USER"), any(), any(), any(), any(), eq(true), any(), any()))
                .thenReturn(PageResult.of(List.of(entity), 1L, 50L, 1L));
        when(auditLogQueryService.exportCsv(any(), any(), any(), any(), any(), any(), any(), any(), eq(2000)))
                .thenReturn("\uFEFFid,type\r\n1,IDENTITY_USER\r\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .param("type", "IDENTITY_USER")
                        .param("success", "true")
                        .header("Authorization", "Bearer admin-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].subType").value("ADMIN_CREATE"));

        mockMvc.perform(get("/api/v1/admin/audit-logs/export")
                        .header("Authorization", "Bearer admin-jwt"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"audit-logs.csv\""))
                .andExpect(content().contentType("text/csv;charset=UTF-8"));
    }
}
