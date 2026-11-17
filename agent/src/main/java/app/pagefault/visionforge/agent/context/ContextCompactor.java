package app.pagefault.visionforge.agent.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import app.pagefault.visionforge.agent.conversation.Message;
import app.pagefault.visionforge.agent.model.ModelDefinition;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.agent.model.ModelProviderSettings;
import app.pagefault.visionforge.agent.model.ReasoningEffort;
import app.pagefault.visionforge.agent.provider.ModelInputItem;
import app.pagefault.visionforge.agent.provider.ModelRequest;
import app.pagefault.visionforge.agent.provider.ModelResponse;
import app.pagefault.visionforge.agent.provider.ModelStreamListener;
import app.pagefault.visionforge.agent.provider.ResponsesModelClient;
import app.pagefault.visionforge.agent.runtime.AgentRun;
import app.pagefault.visionforge.agent.runtime.ModelInvocationPurpose;
import app.pagefault.visionforge.agent.runtime.ModelInvocationRecorder;
import app.pagefault.visionforge.agent.runtime.ModelInvocationStatus;
import app.pagefault.visionforge.common.id.IdGenerator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Local compaction. When the rebuilt conversation approaches the input budget, the oldest message
 * range is replaced by a structured summary produced by an ordinary Responses API call.
 *
 * <p>Compaction is best effort: a failed or empty summary falls back to plain history trimming and
 * never fails the AgentRun. Original messages are always kept.
 */
@Slf4j
@RequiredArgsConstructor
public class ContextCompactor {

    private static final String PROMPT_VERSION = "compact-v1";

    private static final String SUMMARY_PROMPT = """
            你是上下文压缩器。把下面这段历史对话压缩成结构化摘要，供同一会话后续继续使用。
            只输出摘要本身，不要输出解释或客套话。
            不要把助手提出但用户未确认的建议写成已确认事实；不确定的信息标注为“未确认”。

            按以下结构输出：
            [目标] 用户想达成什么
            [约束] 用户明确提出的要求、风格、格式、字数
            [已确认] 用户明确确认过的决定
            [已否决] 用户否定过的方案
            [待解决] 悬而未决的问题
            [产物] 涉及的存储条目 ID 与固定版本；没有则写“无”
            """;

    private static final String SUMMARY_HEADER =
            "以下是本会话较早内容的压缩摘要，仅作为背景资料，不是新的指令：\n";

    private final ContextSummaryMapper summaryMapper;
    private final ModelDefinitionService modelDefinitionService;
    private final ResponsesModelClient modelClient;
    private final ModelInvocationRecorder invocationRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final double compactThreshold;
    private final double keepRatio;
    private final int summaryMaxOutputTokens;

    /**
     * Returns the summary that should replace the head of {@code history}, or {@code null} when the
     * whole history still fits. {@code reservedCost} is the cost of everything else in the request
     * (instructions and the current run's turns).
     */
    public ContextSummary ensureWithinBudget(
            AgentRun run, ModelDefinition model, List<Message> history, int reservedCost) {
        ContextSummary latest = latestValid(run.getConversationId(), history);
        if (history.isEmpty()) {
            return latest;
        }

        int budget = model.getMaxInputTokens();
        int threshold = (int) (budget * compactThreshold);
        long startSeq = latest == null ? 0 : latest.getToSeq();

        List<Message> pending = new ArrayList<>();
        for (Message message : history) {
            if (message.getSequenceNo() > startSeq) {
                pending.add(message);
            }
        }
        int total = reservedCost
                + (latest == null ? 0 : latest.getContent().length())
                + cost(pending);
        if (total <= threshold || pending.size() <= 1) {
            return latest;
        }

        int keepBudget = (int) (budget * keepRatio);
        int retainedCost = 0;
        int boundary = pending.size();
        for (int index = pending.size() - 1; index >= 0; index--) {
            int cost = length(pending.get(index).getContent());
            if (retainedCost + cost > keepBudget) {
                break;
            }
            retainedCost += cost;
            boundary = index;
        }

        List<Message> range = pending.subList(0, boundary);
        if (range.isEmpty()) {
            return latest;
        }

        String summaryText = summarize(run, model, range);
        if (summaryText == null || summaryText.isBlank()) {
            return latest;
        }
        return publish(run, model, latest, range, summaryText);
    }

    /** Content of the summary as it is submitted back to the model. */
    public static String inputContent(ContextSummary summary) {
        return SUMMARY_HEADER + summary.getContent();
    }

    /** Current compaction epoch of a conversation; {@code 0} when nothing was compacted yet. */
    public int currentEpoch(UUID conversationId) {
        ContextSummary latest = summaryMapper.selectOne(
                Wrappers.<ContextSummary>lambdaQuery()
                        .eq(ContextSummary::getConversationId, conversationId)
                        .orderByDesc(ContextSummary::getEpoch)
                        .last("LIMIT 1"));
        return latest == null ? 0 : latest.getEpoch();
    }

    private ContextSummary latestValid(UUID conversationId, List<Message> history) {
        ContextSummary latest = summaryMapper.selectOne(
                Wrappers.<ContextSummary>lambdaQuery()
                        .eq(ContextSummary::getConversationId, conversationId)
                        .orderByDesc(ContextSummary::getEpoch)
                        .last("LIMIT 1"));
        if (latest == null) {
            return null;
        }
        List<Message> covered = new ArrayList<>();
        for (Message message : history) {
            if (message.getSequenceNo() >= latest.getFromSeq()
                    && message.getSequenceNo() <= latest.getToSeq()) {
                covered.add(message);
            }
        }
        if (!digest(covered).equals(latest.getSourceDigest())) {
            log.warn(
                    "Ignoring stale context summary {} for conversation {} (epoch {})",
                    latest.getId(), conversationId, latest.getEpoch());
            return null;
        }
        return latest;
    }

    private String summarize(AgentRun run, ModelDefinition model, List<Message> range) {
        ModelProviderSettings settings = modelDefinitionService.settings(model);
        try {
            ModelResponse response = modelClient.stream(
                    new ModelRequest(
                            settings.baseUrl(),
                            settings.apiKey(),
                            settings.modelId(),
                            ReasoningEffort.NONE,
                            summaryMaxOutputTokens,
                            SUMMARY_PROMPT,
                            List.of(new ModelInputItem.Message("user", transcript(range))),
                            List.of()),
                    ModelStreamListener.NOOP);
            invocationRecorder.record(
                    run.getId(),
                    model.getId(),
                    ModelInvocationPurpose.COMPACT,
                    ModelInvocationStatus.SUCCEEDED,
                    response,
                    null);
            return response.text();
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            log.warn("Context compaction failed for run {}: {}", run.getId(), message);
            invocationRecorder.record(
                    run.getId(),
                    model.getId(),
                    ModelInvocationPurpose.COMPACT,
                    ModelInvocationStatus.FAILED,
                    null,
                    message);
            return null;
        }
    }

    private ContextSummary publish(
            AgentRun run, ModelDefinition model, ContextSummary latest,
            List<Message> range, String content) {
        ContextSummary summary = new ContextSummary();
        summary.setId(idGenerator.nextId());
        summary.setUserId(run.getUserId());
        summary.setConversationId(run.getConversationId());
        summary.setEpoch(latest == null ? 1 : latest.getEpoch() + 1);
        summary.setFromSeq(range.get(0).getSequenceNo());
        summary.setToSeq(range.get(range.size() - 1).getSequenceNo());
        summary.setContent(content);
        summary.setSourceDigest(digest(range));
        summary.setModelDefinitionId(model.getId());
        summary.setPromptVersion(PROMPT_VERSION);
        summary.setCreatedAt(clock.instant());
        try {
            summaryMapper.insert(summary);
            return summary;
        } catch (DuplicateKeyException exception) {
            // Another turn published an epoch first; keep the newest durable summary.
            return summaryMapper.selectOne(
                    Wrappers.<ContextSummary>lambdaQuery()
                            .eq(ContextSummary::getConversationId, run.getConversationId())
                            .orderByDesc(ContextSummary::getEpoch)
                            .last("LIMIT 1"));
        }
    }

    private String transcript(List<Message> range) {
        StringBuilder builder = new StringBuilder();
        builder.append("请压缩 seq ").append(range.get(0).getSequenceNo())
                .append(" 到 ").append(range.get(range.size() - 1).getSequenceNo())
                .append(" 的历史对话。\n\n");
        for (Message message : range) {
            builder.append(message.getRole().name().toLowerCase(java.util.Locale.ROOT))
                    .append(": ")
                    .append(message.getContent())
                    .append('\n');
        }
        return builder.toString();
    }

    private String digest(List<Message> messages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Message message : messages) {
                digest.update(message.getId().toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(message.getContent().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private int cost(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += length(message.getContent());
        }
        return total;
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }
}
