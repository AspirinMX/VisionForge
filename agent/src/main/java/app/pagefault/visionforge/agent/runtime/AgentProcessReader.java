package app.pagefault.visionforge.agent.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import app.pagefault.visionforge.agent.provider.ModelReasoning;
import app.pagefault.visionforge.agent.provider.ModelToolCall;
import app.pagefault.visionforge.agent.tool.ToolInvocation;
import app.pagefault.visionforge.agent.tool.ToolInvocationMapper;
import app.pagefault.visionforge.agent.tool.ToolInvocationStatus;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads persisted {@link AgentRunTurn} and {@link ToolInvocation} records in batches to build
 * replayable thinking/tool-call timelines for conversation messages and active runs.
 */
@RequiredArgsConstructor
public class AgentProcessReader {

    private final AgentRunMapper agentRunMapper;
    private final AgentRunTurnMapper agentRunTurnMapper;
    private final ToolInvocationMapper toolInvocationMapper;
    private final ObjectMapper objectMapper;

    public Map<UUID, AgentRunProcessView> byAssistantMessageIds(Collection<UUID> assistantMessageIds) {
        List<UUID> ids = assistantMessageIds == null
                ? List.of()
                : assistantMessageIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<AgentRun> runs = agentRunMapper.selectList(
                Wrappers.<AgentRun>lambdaQuery()
                        .in(AgentRun::getAssistantMessageId, ids));
        if (runs.isEmpty()) {
            return Map.of();
        }
        Map<UUID, AgentRunProcessView> byRun = byRuns(runs);
        Map<UUID, AgentRunProcessView> result = new LinkedHashMap<>();
        for (AgentRun run : runs) {
            if (run.getAssistantMessageId() != null) {
                AgentRunProcessView view = byRun.get(run.getId());
                if (view != null) {
                    result.put(run.getAssistantMessageId(), view);
                }
            }
        }
        return result;
    }

    public AgentRunProcessView forRun(AgentRun run) {
        if (run == null) {
            return null;
        }
        return byRuns(List.of(run)).get(run.getId());
    }

    public Map<UUID, AgentRunProcessView> byRuns(Collection<AgentRun> runs) {
        List<AgentRun> validRuns = runs == null
                ? List.of()
                : runs.stream().filter(Objects::nonNull).toList();
        if (validRuns.isEmpty()) {
            return Map.of();
        }
        List<UUID> runIds = validRuns.stream().map(AgentRun::getId).distinct().toList();
        Map<UUID, List<AgentRunTurn>> turnsByRun = agentRunTurnMapper.selectList(
                        Wrappers.<AgentRunTurn>lambdaQuery()
                                .in(AgentRunTurn::getAgentRunId, runIds)
                                .orderByAsc(AgentRunTurn::getAgentRunId)
                                .orderByAsc(AgentRunTurn::getTurnNo))
                .stream()
                .collect(Collectors.groupingBy(
                        AgentRunTurn::getAgentRunId,
                        LinkedHashMap::new,
                        Collectors.toList()));

        Map<UUID, Map<String, ToolInvocation>> invocationsByRun = new LinkedHashMap<>();
        for (ToolInvocation invocation : toolInvocationMapper.selectList(
                Wrappers.<ToolInvocation>lambdaQuery()
                        .in(ToolInvocation::getAgentRunId, runIds)
                        .orderByAsc(ToolInvocation::getAgentRunId)
                        .orderByAsc(ToolInvocation::getCreatedAt))) {
            invocationsByRun
                    .computeIfAbsent(invocation.getAgentRunId(), ignored -> new LinkedHashMap<>())
                    .put(invocation.getToolCallId(), invocation);
        }

        ToolDisplayFormatter formatter = new ToolDisplayFormatter(objectMapper);
        Map<UUID, AgentRunProcessView> result = new LinkedHashMap<>();
        for (AgentRun run : validRuns) {
            List<AgentRunTurn> turns = turnsByRun.getOrDefault(run.getId(), List.of());
            Map<String, ToolInvocation> invocations =
                    invocationsByRun.getOrDefault(run.getId(), Map.of());
            AgentRunProcessView view = buildView(run, turns, invocations, formatter);
            if (view != null) {
                result.put(run.getId(), view);
            }
        }
        return result;
    }

    private AgentRunProcessView buildView(
            AgentRun run,
            List<AgentRunTurn> turns,
            Map<String, ToolInvocation> invocations,
            ToolDisplayFormatter formatter) {
        if (turns.isEmpty()) {
            return null;
        }
        List<AgentTurnProcessView> turnViews = new ArrayList<>(turns.size());
        for (AgentRunTurn turn : turns) {
            String reasoning = extractReasoning(turn.getReasoning());
            List<ModelToolCall> calls = parseCalls(turn.getFunctionCalls());
            List<ToolInvocationSummary> toolSummaries = new ArrayList<>(calls.size());
            for (ModelToolCall call : calls) {
                ToolInvocation invocation = invocations.get(call.callId());
                ToolInvocationStatus status = invocation != null
                        ? invocation.getStatus()
                        : ToolInvocationStatus.RUNNING;
                String resultJson = invocation == null ? null : invocation.getResult();
                String argsJson = invocation != null && invocation.getArguments() != null
                        ? invocation.getArguments()
                        : call.arguments();
                ToolDisplayFormatter.Display display =
                        formatter.format(call.name(), argsJson, resultJson);
                toolSummaries.add(new ToolInvocationSummary(
                        call.callId(),
                        call.name(),
                        display.action(),
                        display.target(),
                        status,
                        display.fileId(),
                        display.taskId(),
                        invocation == null ? null : invocation.getErrorMessage(),
                        invocation == null ? null : invocation.getCreatedAt(),
                        invocation == null ? null : invocation.getCompletedAt()));
            }
            String stepText = calls.isEmpty() || turn.getAssistantText() == null
                    ? null
                    : blankToNull(turn.getAssistantText());
            if (reasoning != null || stepText != null || !toolSummaries.isEmpty()) {
                turnViews.add(new AgentTurnProcessView(
                        turn.getTurnNo(),
                        reasoning,
                        stepText,
                        List.copyOf(toolSummaries)));
            }
        }
        if (turnViews.isEmpty()) {
            return null;
        }
        return new AgentRunProcessView(run.getId(), computeDurationMs(run), List.copyOf(turnViews));
    }

    private Long computeDurationMs(AgentRun run) {
        Instant start = run.getStartedAt() != null ? run.getStartedAt() : run.getCreatedAt();
        if (start == null || run.getCompletedAt() == null) {
            return null;
        }
        return Math.max(0L, Duration.between(start, run.getCompletedAt()).toMillis());
    }

    private String extractReasoning(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            ModelReasoning[] items = objectMapper.readValue(json, ModelReasoning[].class);
            List<String> blocks = new ArrayList<>();
            for (ModelReasoning item : items) {
                if (item.text() != null && !item.text().isBlank()) {
                    blocks.add(item.text().trim());
                } else if (!item.summary().isEmpty()) {
                    String joinedSummary = item.summary().stream()
                            .filter(line -> line != null && !line.isBlank())
                            .map(String::trim)
                            .collect(Collectors.joining("\n"));
                    if (!joinedSummary.isBlank()) {
                        blocks.add(joinedSummary);
                    }
                }
            }
            return blocks.isEmpty() ? null : String.join("\n\n", blocks);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private List<ModelToolCall> parseCalls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(objectMapper.readValue(json, ModelToolCall[].class));
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
