package app.pagefault.visionforge.agent.conversation;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.model.ModelDefinition;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.agent.runtime.AgentRun;
import app.pagefault.visionforge.agent.runtime.AgentRunMapper;
import app.pagefault.visionforge.agent.runtime.AgentRunStatus;
import app.pagefault.visionforge.agent.runtime.WorkflowStartRequest;
import app.pagefault.visionforge.agent.runtime.WorkflowStartRequestMapper;
import app.pagefault.visionforge.agent.runtime.WorkflowStartStatus;
import app.pagefault.visionforge.agent.runtime.WorkflowStartType;
import app.pagefault.visionforge.asset.media.Asset;
import app.pagefault.visionforge.asset.media.AssetService;
import app.pagefault.visionforge.asset.media.AssetStatus;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.asset.error.AssetErrorCode;
import app.pagefault.visionforge.common.error.CommonErrorCode;
import app.pagefault.visionforge.common.id.IdGenerator;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ConversationService {

    private static final String DEFAULT_TITLE = "新对话";
    private static final int TITLE_MAX_LENGTH = 200;
    private static final int MESSAGE_MAX_LENGTH = 32_000;

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final MessagePartMapper messagePartMapper;
    private final AgentRunMapper agentRunMapper;
    private final WorkflowStartRequestMapper workflowStartRequestMapper;
    private final ModelDefinitionService modelDefinitionService;
    private final AssetService assetService;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public Conversation create(UUID userId, String title) {
        Instant now = clock.instant();
        ModelDefinition defaultModel = modelDefinitionService.findDefaultEnabledOrNull();

        Conversation conversation = new Conversation();
        conversation.setId(idGenerator.nextId());
        conversation.setCreatedBy(userId);
        conversation.setTitle(normalizeTitle(title));
        conversation.setModelDefinitionId(defaultModel == null ? null : defaultModel.getId());
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);
        return conversation;
    }

    public List<Conversation> list(UUID userId) {
        return conversationMapper.selectList(
                Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getCreatedBy, userId)
                        .orderByDesc(Conversation::getLastMessageAt)
                        .orderByDesc(Conversation::getCreatedAt));
    }

    public Conversation get(UUID userId, UUID conversationId) {
        Conversation conversation = conversationId == null
                ? null
                : conversationMapper.selectById(conversationId);
        if (conversation == null || !userId.equals(conversation.getCreatedBy())) {
            throw new BusinessException(AgentErrorCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    public List<Message> messages(UUID userId, UUID conversationId) {
        get(userId, conversationId);
        return messageMapper.selectList(
                Wrappers.<Message>lambdaQuery()
                        .eq(Message::getConversationId, conversationId)
                        .orderByAsc(Message::getSequenceNo));
    }

    /**
     * Messages plus their attachment parts, loaded with one extra query for the whole conversation.
     */
    public List<MessageWithParts> messagesWithParts(UUID userId, UUID conversationId) {
        List<Message> messages = messages(userId, conversationId);
        if (messages.isEmpty()) {
            return List.of();
        }
        List<UUID> messageIds = messages.stream().map(Message::getId).toList();
        Map<UUID, List<MessagePart>> partsByMessage = messagePartMapper.selectList(
                        Wrappers.<MessagePart>lambdaQuery()
                                .in(MessagePart::getMessageId, messageIds)
                                .orderByAsc(MessagePart::getMessageId)
                                .orderByAsc(MessagePart::getOrdinal))
                .stream()
                .collect(Collectors.groupingBy(
                        MessagePart::getMessageId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        return messages.stream()
                .map(message -> {
                    List<MessagePart> parts =
                            partsByMessage.getOrDefault(message.getId(), List.of());
                    return new MessageWithParts(
                            message,
                            parts.stream()
                                    .filter(part -> part.getPartType()
                                            == MessagePartType.ASSET_REFERENCE)
                                    .map(MessagePart::getAssetId)
                                    .filter(java.util.Objects::nonNull)
                                    .toList(),
                            parts.stream()
                                    .filter(part -> part.getPartType()
                                            == MessagePartType.GENERATION_TASK)
                                    .map(MessagePart::getGenerationTaskId)
                                    .filter(java.util.Objects::nonNull)
                                    .toList());
                })
                .toList();
    }

    public AgentTurnResult startTurn(UUID userId, UUID conversationId, String content) {
        return startTurn(userId, conversationId, content, List.of());
    }

    @Transactional
    public AgentTurnResult startTurn(
            UUID userId, UUID conversationId, String content, List<UUID> assetIds) {
        Conversation conversation = get(userId, conversationId);
        // Serialize per conversation: message sequence allocation and run creation must not race
        // with another turn of the same conversation.
        conversationMapper.selectForUpdate(conversationId);
        if (hasActiveRun(conversationId)) {
            throw new BusinessException(AgentErrorCode.CONVERSATION_BUSY);
        }
        String safeContent = normalizeContent(content);
        Instant now = clock.instant();

        Message userMessage = new Message();
        userMessage.setId(idGenerator.nextId());
        userMessage.setConversationId(conversationId);
        userMessage.setSenderUserId(userId);
        userMessage.setRole(MessageRole.USER);
        userMessage.setContent(safeContent);
        userMessage.setSequenceNo(nextSequence(conversationId));
        userMessage.setStatus(MessageStatus.COMPLETED);
        userMessage.setCreatedAt(now);
        messageMapper.insert(userMessage);
        attachAssets(userId, conversationId, userMessage.getId(), assetIds, now);

        UUID runId = idGenerator.nextId();
        String workflowId = "agent-run-" + runId;
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setConversationId(conversationId);
        run.setUserMessageId(userMessage.getId());
        run.setUserId(userId);
        run.setModelDefinitionId(conversation.getModelDefinitionId());
        run.setStatus(AgentRunStatus.PENDING);
        run.setWorkflowId(workflowId);
        run.setCreatedAt(now);
        try {
            agentRunMapper.insert(run);
        } catch (DuplicateKeyException exception) {
            // The partial unique index on active runs is the storage-level guard.
            throw new BusinessException(AgentErrorCode.CONVERSATION_BUSY);
        }

        WorkflowStartRequest startRequest = new WorkflowStartRequest();
        startRequest.setId(idGenerator.nextId());
        startRequest.setAgentRunId(runId);
        startRequest.setWorkflowType(WorkflowStartType.AGENT_RUN);
        startRequest.setBusinessId(runId);
        startRequest.setInputSchemaVersion(1);
        startRequest.setWorkflowId(workflowId);
        startRequest.setStatus(WorkflowStartStatus.PENDING);
        startRequest.setAttempts(0);
        startRequest.setCreatedAt(now);
        startRequest.setUpdatedAt(now);
        workflowStartRequestMapper.insert(startRequest);

        if (DEFAULT_TITLE.equals(conversation.getTitle())) {
            conversation.setTitle(deriveTitle(safeContent));
        }
        conversation.setLastMessageAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.updateById(conversation);

        return new AgentTurnResult(runId, workflowId, userMessage.getId());
    }

    private void attachAssets(
            UUID userId,
            UUID conversationId,
            UUID messageId,
            List<UUID> assetIds,
            Instant now) {
        if (assetIds == null || assetIds.isEmpty()) {
            return;
        }
        List<UUID> distinct = assetIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.size() > 8) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_ARGUMENT, "单条消息最多附带 8 个素材");
        }
        int ordinal = 0;
        for (UUID assetId : distinct) {
            Asset asset = assetService.getForConversation(userId, conversationId, assetId);
            if (asset.getStatus() != AssetStatus.READY) {
                throw new BusinessException(AssetErrorCode.ASSET_NOT_READY);
            }
            MessagePart part = new MessagePart();
            part.setId(idGenerator.nextId());
            part.setMessageId(messageId);
            part.setPartType(MessagePartType.ASSET_REFERENCE);
            part.setOrdinal(ordinal++);
            part.setAssetId(assetId);
            part.setCreatedAt(now);
            messagePartMapper.insert(part);
        }
    }

    private boolean hasActiveRun(UUID conversationId) {
        return agentRunMapper.selectCount(
                Wrappers.<AgentRun>lambdaQuery()
                        .eq(AgentRun::getConversationId, conversationId)
                        .in(AgentRun::getStatus, AgentRunStatus.PENDING, AgentRunStatus.RUNNING))
                > 0;
    }

    private long nextSequence(UUID conversationId) {
        Message last = messageMapper.selectOne(
                Wrappers.<Message>lambdaQuery()
                        .eq(Message::getConversationId, conversationId)
                        .orderByDesc(Message::getSequenceNo)
                        .last("LIMIT 1"));
        return last == null ? 1 : last.getSequenceNo() + 1;
    }

    private String normalizeTitle(String title) {
        String value = title == null ? "" : title.trim();
        if (value.isEmpty()) {
            return DEFAULT_TITLE;
        }
        if (value.length() > TITLE_MAX_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "会话标题不能超过 200 个字符");
        }
        return value;
    }

    private String normalizeContent(String content) {
        String value = content == null ? "" : content.trim();
        if (value.isEmpty()) {
            throw new BusinessException(AgentErrorCode.MESSAGE_EMPTY);
        }
        if (value.length() > MESSAGE_MAX_LENGTH) {
            throw new BusinessException(AgentErrorCode.MESSAGE_TOO_LONG);
        }
        return value;
    }

    private String deriveTitle(String content) {
        int length = Math.min(content.length(), 40);
        return length == content.length() ? content : content.substring(0, length) + "…";
    }
}
