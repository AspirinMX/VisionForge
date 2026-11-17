package app.pagefault.visionforge.api.asset.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.asset.library.ConversationItemRole;
import app.pagefault.visionforge.asset.library.ConversationItemView;

public record ConversationItemResponse(
        WorkspaceItemResponse item,
        ConversationItemRole role,
        Integer pinnedVersion,
        UUID addedBy,
        Instant addedAt) {

    public static ConversationItemResponse from(ConversationItemView view) {
        return new ConversationItemResponse(
                WorkspaceItemResponse.from(view.item()),
                view.binding().getRole(),
                view.binding().getPinnedVersion(),
                view.binding().getAddedBy(),
                view.binding().getAddedAt());
    }
}
