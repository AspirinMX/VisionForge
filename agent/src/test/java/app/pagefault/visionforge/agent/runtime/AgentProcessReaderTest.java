package app.pagefault.visionforge.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import app.pagefault.visionforge.agent.tool.ToolInvocation;
import app.pagefault.visionforge.agent.tool.ToolInvocationMapper;
import app.pagefault.visionforge.agent.tool.ToolInvocationStatus;

import tools.jackson.databind.ObjectMapper;

class AgentProcessReaderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsTimelineExcludingEncryptedReasoningAndFinalTurnText() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRunTurnMapper turnMapper = mock(AgentRunTurnMapper.class);
        ToolInvocationMapper invocationMapper = mock(ToolInvocationMapper.class);
        AgentProcessReader reader = new AgentProcessReader(
                runMapper, turnMapper, invocationMapper, objectMapper);

        UUID messageId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setAssistantMessageId(messageId);
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setStartedAt(Instant.parse("2026-11-19T01:00:00Z"));
        run.setCompletedAt(Instant.parse("2026-11-19T01:00:18Z"));

        AgentRunTurn turn0 = new AgentRunTurn();
        turn0.setAgentRunId(runId);
        turn0.setTurnNo(0);
        turn0.setReasoning("""
                [{"encryptedContent":"cipher_secret","text":null,"summary":["先创建剧本文件","再生成分镜首帧"]}]
                """);
        turn0.setAssistantText("我先在工作区为你建立剧本文档并起草分镜。");
        turn0.setFunctionCalls("""
                [
                  {"callId":"c1","name":"create_file","arguments":"{\\"kind\\":\\"SCRIPT\\",\\"title\\":\\"深夜咖啡馆\\",\\"path\\":\\"script.md\\",\\"content\\":\\"...\\"}"},
                  {"callId":"c2","name":"generate_image","arguments":"{\\"shotId\\":\\"S01\\",\\"prompt\\":\\"雨夜霓虹咖啡馆窗景\\"}"}
                ]
                """);

        AgentRunTurn turn1 = new AgentRunTurn();
        turn1.setAgentRunId(runId);
        turn1.setTurnNo(1);
        turn1.setReasoning("""
                [{"encryptedContent":"cipher_2","text":"汇总最终分镜方案","summary":[]}]
                """);
        turn1.setAssistantText("这是最终输出正文，不应该重复出现在思考过程里。");
        turn1.setFunctionCalls(null);

        ToolInvocation inv1 = new ToolInvocation();
        inv1.setAgentRunId(runId);
        inv1.setToolCallId("c1");
        inv1.setToolName("create_file");
        inv1.setStatus(ToolInvocationStatus.SUCCEEDED);
        inv1.setResult("{\"file\":{\"fileId\":\"" + fileId + "\",\"path\":\"script.md\",\"title\":\"深夜咖啡馆\"}}");

        ToolInvocation inv2 = new ToolInvocation();
        inv2.setAgentRunId(runId);
        inv2.setToolCallId("c2");
        inv2.setToolName("generate_image");
        inv2.setStatus(ToolInvocationStatus.SUCCEEDED);
        inv2.setResult("{\"taskId\":\"" + taskId + "\",\"status\":\"ACCEPTED\"}");

        when(runMapper.selectList(any())).thenReturn(List.of(run));
        when(turnMapper.selectList(any())).thenReturn(List.of(turn0, turn1));
        when(invocationMapper.selectList(any())).thenReturn(List.of(inv1, inv2));

        Map<UUID, AgentRunProcessView> result = reader.byAssistantMessageIds(List.of(messageId));
        AgentRunProcessView process = result.get(messageId);

        assertThat(process).isNotNull();
        assertThat(process.durationMs()).isEqualTo(18_000L);
        assertThat(process.turns()).hasSize(2);

        AgentTurnProcessView firstTurn = process.turns().get(0);
        assertThat(firstTurn.reasoning()).isEqualTo("先创建剧本文件\n再生成分镜首帧");
        assertThat(firstTurn.reasoning()).doesNotContain("cipher_secret");
        assertThat(firstTurn.stepText()).isEqualTo("我先在工作区为你建立剧本文档并起草分镜。");
        assertThat(firstTurn.toolCalls()).hasSize(2);
        assertThat(firstTurn.toolCalls().get(0).action()).isEqualTo("创建文件");
        assertThat(firstTurn.toolCalls().get(0).target()).isEqualTo("script.md");
        assertThat(firstTurn.toolCalls().get(0).fileId()).isEqualTo(fileId);
        assertThat(firstTurn.toolCalls().get(1).action()).isEqualTo("生成图片");
        assertThat(firstTurn.toolCalls().get(1).target()).isEqualTo("第 1 镜");
        assertThat(firstTurn.toolCalls().get(1).taskId()).isEqualTo(taskId);

        AgentTurnProcessView secondTurn = process.turns().get(1);
        assertThat(secondTurn.reasoning()).isEqualTo("汇总最终分镜方案");
        assertThat(secondTurn.stepText()).isNull();
        assertThat(secondTurn.toolCalls()).isEmpty();
    }

    @Test
    void formatsAllToolsWithoutExposingRawUuidsOrInvalidJsonFailure() {
        ToolDisplayFormatter formatter = new ToolDisplayFormatter(objectMapper);
        UUID sbFileId = UUID.randomUUID();
        UUID compTaskId = UUID.randomUUID();

        ToolDisplayFormatter.Display skill =
                formatter.format("load_skill", "{\"name\":\"short-film\"}", null);
        assertThat(skill.action()).isEqualTo("读取创作指南");
        assertThat(skill.target()).isEqualTo("short-film");

        ToolDisplayFormatter.Display storyboard = formatter.format(
                "save_storyboard",
                "{\"storyboard\":{\"title\":\"咖啡馆偶遇\"}}",
                "{\"fileId\":\"" + sbFileId + "\",\"title\":\"咖啡馆偶遇\"}");
        assertThat(storyboard.action()).isEqualTo("保存分镜");
        assertThat(storyboard.target()).isEqualTo("咖啡馆偶遇");
        assertThat(storyboard.fileId()).isEqualTo(sbFileId);

        ToolDisplayFormatter.Display compose = formatter.format(
                "compose_video",
                "{\"storyboardVersion\":3,\"storyboardFileId\":\"" + sbFileId + "\"}",
                "{\"taskId\":\"" + compTaskId + "\"}");
        assertThat(compose.action()).isEqualTo("合成成片");
        assertThat(compose.target()).isEqualTo("分镜合成");
        assertThat(compose.taskId()).isEqualTo(compTaskId);

        ToolDisplayFormatter.Display broken = formatter.format("validate_storyboard", "{bad", "not json");
        assertThat(broken.action()).isEqualTo("校验分镜结构");
        assertThat(broken.target()).isNull();
    }
}
