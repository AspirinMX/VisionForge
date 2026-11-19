package app.pagefault.visionforge.api.alert;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

import app.pagefault.visionforge.agent.alert.AlertChannel;
import app.pagefault.visionforge.agent.alert.AlertChannelService;
import app.pagefault.visionforge.agent.alert.AlertChannelType;
import app.pagefault.visionforge.agent.alert.AlertDelivery;
import app.pagefault.visionforge.agent.alert.AlertDeliveryStatus;
import app.pagefault.visionforge.agent.alert.AlertDispatchService;
import app.pagefault.visionforge.api.security.SecurityConfiguration;
import app.pagefault.visionforge.common.alert.AlertSeverity;
import app.pagefault.visionforge.common.api.PageResult;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminAlertController.class)
@Import(SecurityConfiguration.class)
class AdminAlertControllerTest {

    private static final UUID ADMIN_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-000000000001");
    private static final UUID USER_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private AlertChannelService channelService;

    @MockitoBean
    private AlertDispatchService dispatchService;

    @BeforeEach
    void stubJwt() {
        Instant now = Instant.parse("2026-11-19T00:00:00Z");
        when(jwtDecoder.decode("admin-jwt")).thenReturn(Jwt.withTokenValue("admin-jwt")
                .header("alg", "HS256")
                .subject(ADMIN_ID.toString())
                .claim("role", "ADMIN")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build());
        when(jwtDecoder.decode("user-jwt")).thenReturn(Jwt.withTokenValue("user-jwt")
                .header("alg", "HS256")
                .subject(USER_ID.toString())
                .claim("role", "USER")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build());
    }

    @Test
    void enforcesAdminRoleAndExposesAlertChannelLifecycle() throws Exception {
        mockMvc.perform(get("/api/v1/admin/alerts/channels")
                        .header("Authorization", "Bearer user-jwt"))
                .andExpect(status().isForbidden());

        UUID channelId = UUID.randomUUID();
        AlertChannel channel = new AlertChannel();
        channel.setId(channelId);
        channel.setName("Ops Webhook");
        channel.setChannelType(AlertChannelType.WEBHOOK);
        channel.setTarget("https://8.8.8.8/hook");
        channel.setSecretCiphertext("enc-secret");
        channel.setSubscribedEvents("*");
        channel.setMinSeverity(AlertSeverity.WARNING);
        channel.setEnabled(true);
        channel.setCreatedAt(Instant.parse("2026-11-19T00:00:00Z"));
        channel.setUpdatedAt(Instant.parse("2026-11-19T00:00:00Z"));

        AlertDelivery delivery = new AlertDelivery();
        delivery.setId(UUID.randomUUID());
        delivery.setChannelId(channelId);
        delivery.setChannelName("Ops Webhook");
        delivery.setChannelType(AlertChannelType.WEBHOOK);
        delivery.setEventType("ALERT_TEST");
        delivery.setSeverity(AlertSeverity.INFO);
        delivery.setTitle("Test");
        delivery.setSummary("Ok");
        delivery.setStatus(AlertDeliveryStatus.SENT);
        delivery.setHttpStatus(200);
        delivery.setAttemptCount(1);
        delivery.setCreatedAt(Instant.parse("2026-11-19T00:00:00Z"));

        when(channelService.listChannels()).thenReturn(List.of(channel));
        when(channelService.createChannel(eq(ADMIN_ID), any())).thenReturn(channel);
        when(channelService.updateChannel(eq(channelId), any())).thenReturn(channel);
        when(dispatchService.testChannel(channelId)).thenReturn(delivery);
        when(channelService.listDeliveries(1L, 50L, null, null, null))
                .thenReturn(PageResult.of(List.of(delivery), 1L, 50L, 1L));

        mockMvc.perform(post("/api/v1/admin/alerts/channels")
                        .header("Authorization", "Bearer admin-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ops Webhook\",\"channelType\":\"WEBHOOK\",\"target\":\"https://8.8.8.8/hook\",\"secret\":\"sec\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.secretConfigured").value(true));

        mockMvc.perform(patch("/api/v1/admin/alerts/channels/" + channelId)
                        .header("Authorization", "Bearer admin-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(channelId.toString()));

        mockMvc.perform(post("/api/v1/admin/alerts/channels/" + channelId + "/test")
                        .header("Authorization", "Bearer admin-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SENT"))
                .andExpect(jsonPath("$.data.httpStatus").value(200));

        mockMvc.perform(get("/api/v1/admin/alerts/deliveries")
                        .header("Authorization", "Bearer admin-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].eventType").value("ALERT_TEST"));

        mockMvc.perform(delete("/api/v1/admin/alerts/channels/" + channelId)
                        .header("Authorization", "Bearer admin-jwt"))
                .andExpect(status().isOk());
        verify(channelService).deleteChannel(channelId);
    }
}
