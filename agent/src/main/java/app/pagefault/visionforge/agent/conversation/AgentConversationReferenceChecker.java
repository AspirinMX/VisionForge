package app.pagefault.visionforge.agent.conversation;

import java.util.UUID;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.asset.library.ConversationReferenceChecker;
import app.pagefault.visionforge.common.error.BusinessException;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class AgentConversationReferenceChecker implements ConversationReferenceChecker {

    private final ConversationMapper conversationMapper;

    @Override
    public void requireOwned(UUID userId, UUID conversationId) {
        Conversation conversation = conversationId == null
                ? null
                : conversationMapper.selectById(conversationId);
        if (conversation == null || !userId.equals(conversation.getCreatedBy())) {
            throw new BusinessException(AgentErrorCode.CONVERSATION_NOT_FOUND);
        }
    }
}
