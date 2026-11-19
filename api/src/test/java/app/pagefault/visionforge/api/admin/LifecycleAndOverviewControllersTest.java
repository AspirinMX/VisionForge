package app.pagefault.visionforge.api.admin;

import java.time.Instant;
import java.time.LocalDate;
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

import app.pagefault.visionforge.agent.conversation.Conversation;
import app.pagefault.visionforge.agent.conversation.ConversationService;
import app.pagefault.visionforge.agent.model.ExecutionMode;
import app.pagefault.visionforge.agent.model.MediaProtocol;
import app.pagefault.visionforge.agent.model.ModelCapabilityType;
import app.pagefault.visionforge.agent.model.ModelConnectivityService;
import app.pagefault.visionforge.agent.model.ModelDefinition;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.agent.model.ReasoningEffort;
import app.pagefault.visionforge.agent.model.ReasoningReplayPolicy;
import app.pagefault.visionforge.agent.runtime.AgentProcessReader;
import app.pagefault.visionforge.agent.runtime.AgentRun;
import app.pagefault.visionforge.agent.runtime.AgentRunProcessView;
import app.pagefault.visionforge.agent.runtime.AgentRunService;
import app.pagefault.visionforge.agent.runtime.AgentRunStatus;
import app.pagefault.visionforge.agent.stream.AgentRunStreamPublisher;
import app.pagefault.visionforge.api.admin.dto.AdminDashboardResponse;
import app.pagefault.visionforge.api.admin.dto.AdminOverviewResponse;
import app.pagefault.visionforge.api.agent.AgentRunController;
import app.pagefault.visionforge.api.agent.AgentRunStarter;
import app.pagefault.visionforge.api.agent.ConversationController;
import app.pagefault.visionforge.api.model.AdminModelController;
import app.pagefault.visionforge.api.security.SecurityConfiguration;
import app.pagefault.visionforge.api.stream.AgentRunStreamService;
import app.pagefault.visionforge.asset.library.ConversationReferenceChecker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        ConversationController.class,
        AgentRunController.class,
        AdminModelController.class,
        AdminOverviewController.class,
        AdminDashboardController.class
})
@Import(SecurityConfiguration.class)
class LifecycleAndOverviewControllersTest {

    private static final UUID ADMIN_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-000000000001");
    private static final UUID USER_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private ConversationService conversationService;

    @MockitoBean
    private AgentRunStarter agentRunStarter;

    @MockitoBean
    private AgentRunStreamService agentRunStreamService;

    @MockitoBean
    private AgentRunService agentRunService;

    @MockitoBean
    private AgentProcessReader agentProcessReader;

    @MockitoBean
    private ConversationReferenceChecker conversationReferenceChecker;

    @MockitoBean
    private AgentRunStreamPublisher streamPublisher;

    @MockitoBean
    private ModelDefinitionService modelDefinitionService;

    @MockitoBean
    private ModelConnectivityService modelConnectivityService;

    @MockitoBean
    private AdminOverviewService adminOverviewService;

    @MockitoBean
    private AdminDashboardService adminDashboardService;

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
    void userCanRenameDeleteConversationAndCancelRun() throws Exception {
        UUID convId = UUID.randomUUID();
        Conversation conv = new Conversation();
        conv.setId(convId);
        conv.setTitle("Renamed Title");
        conv.setCreatedAt(Instant.parse("2026-11-19T00:00:00Z"));
        conv.setUpdatedAt(Instant.parse("2026-11-19T00:01:00Z"));
        when(conversationService.rename(USER_ID, convId, "Renamed Title")).thenReturn(conv);

        mockMvc.perform(patch("/api/v1/conversations/" + convId)
                        .header("Authorization", "Bearer user-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Renamed Title\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Renamed Title"));

        mockMvc.perform(delete("/api/v1/conversations/" + convId)
                        .header("Authorization", "Bearer user-jwt"))
                .andExpect(status().isOk());
        verify(conversationService).delete(USER_ID, convId);

        UUID runId = UUID.randomUUID();
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setConversationId(convId);
        run.setStatus(AgentRunStatus.CANCELLED);
        run.setWorkflowId("agent-run-" + runId);
        run.setErrorMessage("用户已取消生成");
        run.setCreatedAt(Instant.parse("2026-11-19T00:00:00Z"));
        run.setCompletedAt(Instant.parse("2026-11-19T00:01:00Z"));
        when(agentRunService.cancel(USER_ID, runId)).thenReturn(run);
        when(agentProcessReader.forRun(run)).thenReturn(new AgentRunProcessView(runId, 0L, java.util.List.of()));

        mockMvc.perform(post("/api/v1/agent-runs/" + runId + "/cancel")
                        .header("Authorization", "Bearer user-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        verify(agentRunStarter).cancel("agent-run-" + runId);
        verify(streamPublisher).publishError(runId, "用户已取消生成");
    }

    @Test
    void adminCanProbeModelConnectivityAndReadOverview() throws Exception {
        UUID modelId = UUID.randomUUID();
        when(modelConnectivityService.testConnection(modelId))
                .thenReturn(new ModelConnectivityService.ProbeResult(true, true, 200, 42L, "OPENAI_RESPONSES", "ok"));
        when(adminOverviewService.snapshot())
                .thenReturn(new AdminOverviewResponse(12, 10, 2, 34, 5, 1, 0, 2, 1, 0, 1, 0, 0));

        mockMvc.perform(post("/api/v1/admin/models/" + modelId + "/test")
                        .header("Authorization", "Bearer admin-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.latencyMs").value(42));

        mockMvc.perform(get("/api/v1/admin/overview")
                        .header("Authorization", "Bearer admin-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalUsers").value(12))
                .andExpect(jsonPath("$.data.archiveFailedGenerationTasks").value(1));

        mockMvc.perform(get("/api/v1/admin/overview")
                        .header("Authorization", "Bearer user-jwt"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReadDashboardMetrics() throws Exception {
        when(adminDashboardService.dashboard(30)).thenReturn(new AdminDashboardResponse(
                30,
                Instant.parse("2026-11-20T06:32:00Z"),
                new AdminDashboardResponse.Totals(
                        8_920_400L, 32_400L, 31_200_000L, 11_600_000L,
                        0.982, 1_204L, 86L),
                new AdminDashboardResponse.Deltas(0.124, 0.083, 0.031, 0.004),
                List.of(new AdminDashboardResponse.DailyPoint(
                        LocalDate.parse("2026-11-20"),
                        31_200_000L, 11_600_000L, 1_866L, 32_400L, 1_832L, 34L, 5L)),
                List.of(new AdminDashboardResponse.LeaderboardEntry(USER_ID, "张三", 320_000L)),
                List.of(),
                new AdminDashboardResponse.Attention(3L, 1L, 0L, 4L, 0L),
                new AdminDashboardResponse.Live(3L, 5L, 1L)));

        mockMvc.perform(get("/api/v1/admin/dashboard?days=30")
                        .header("Authorization", "Bearer admin-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rangeDays").value(30))
                .andExpect(jsonPath("$.data.totals.settledCredits").value(8_920_400))
                .andExpect(jsonPath("$.data.totals.successRate").value(0.982))
                .andExpect(jsonPath("$.data.topUsersByCredits[0].displayName").value("张三"))
                .andExpect(jsonPath("$.data.attention.archiveFailed").value(3))
                .andExpect(jsonPath("$.data.live.activeGenerationTasks").value(5));

        mockMvc.perform(get("/api/v1/admin/dashboard")
                        .header("Authorization", "Bearer user-jwt"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanCreateAndUpdateSupportedReasoningEfforts() throws Exception {
        UUID modelId = UUID.randomUUID();
        ModelDefinition saved = new ModelDefinition();
        saved.setId(modelId);
        saved.setDisplayName("GPT-5 Pro");
        saved.setBaseUrl("https://api.example.com/v1");
        saved.setModelId("gpt-5-pro");
        saved.setReasoningEffort(ReasoningEffort.XHIGH);
        saved.setSupportedReasoningEfforts("[\"NONE\",\"LOW\",\"XHIGH\",\"MAX\"]");
        saved.setReasoningReplay(ReasoningReplayPolicy.ENCRYPTED);
        saved.setCapabilityType(ModelCapabilityType.CHAT);
        saved.setProtocol(MediaProtocol.OPENAI_RESPONSES);
        saved.setExecutionMode(ExecutionMode.INLINE_RESULT);
        saved.setPriceUnits(0L);
        saved.setSupportsImageInput(true);
        saved.setMaxInputTokens(128_000);
        saved.setMaxOutputTokens(16_000);
        saved.setEnabled(true);
        saved.setDefaultModel(false);
        saved.setCreatedAt(Instant.parse("2026-11-19T00:00:00Z"));
        saved.setUpdatedAt(Instant.parse("2026-11-19T00:00:00Z"));

        when(modelDefinitionService.create(any())).thenReturn(saved);
        when(modelDefinitionService.update(eq(modelId), any())).thenReturn(saved);

        mockMvc.perform(post("/api/v1/admin/models")
                        .header("Authorization", "Bearer admin-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName":"GPT-5 Pro",
                                  "baseUrl":"https://api.example.com/v1",
                                  "apiKey":"sk-secret",
                                  "modelId":"gpt-5-pro",
                                  "reasoningEffort":"XHIGH",
                                  "supportedReasoningEfforts":["NONE","LOW","XHIGH","MAX"],
                                  "maxInputTokens":128000,
                                  "maxOutputTokens":16000
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reasoningEffort").value("XHIGH"))
                .andExpect(jsonPath("$.data.supportedReasoningEfforts[0]").value("NONE"))
                .andExpect(jsonPath("$.data.supportedReasoningEfforts[2]").value("XHIGH"))
                .andExpect(jsonPath("$.data.supportedReasoningEfforts[3]").value("MAX"));

        verify(modelDefinitionService).create(argThat(input ->
                input.reasoningEffort() == ReasoningEffort.XHIGH
                        && java.util.List.of(
                                ReasoningEffort.NONE,
                                ReasoningEffort.LOW,
                                ReasoningEffort.XHIGH,
                                ReasoningEffort.MAX)
                        .equals(input.supportedReasoningEfforts())));

        mockMvc.perform(patch("/api/v1/admin/models/" + modelId)
                        .header("Authorization", "Bearer admin-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "supportedReasoningEfforts":["HIGH","MAX"],
                                  "clearReasoningEffort":true
                                }
                                """))
                .andExpect(status().isOk());

        verify(modelDefinitionService).update(eq(modelId), argThat(input ->
                Boolean.TRUE.equals(input.clearReasoningEffort())
                        && java.util.List.of(ReasoningEffort.HIGH, ReasoningEffort.MAX)
                        .equals(input.supportedReasoningEfforts())));
    }
}
