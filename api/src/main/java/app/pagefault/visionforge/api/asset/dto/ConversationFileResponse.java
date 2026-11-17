package app.pagefault.visionforge.api.asset.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.asset.library.ConversationFile;
import app.pagefault.visionforge.asset.library.ConversationFileKind;
import app.pagefault.visionforge.asset.library.ConversationFileStatus;
import app.pagefault.visionforge.asset.library.ConversationFileStorageKind;

public record ConversationFileResponse(
        UUID id,
        UUID conversationId,
        ConversationFileKind kind,
        String title,
        String path,
        String mimeType,
        ConversationFileStorageKind storageKind,
        int currentVersion,
        ConversationFileStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static ConversationFileResponse from(ConversationFile file) {
        return new ConversationFileResponse(
                file.getId(),
                file.getConversationId(),
                file.getKind(),
                file.getTitle(),
                file.getPath(),
                file.getMimeType(),
                file.getStorageKind(),
                file.getCurrentVersion(),
                file.getStatus(),
                file.getCreatedAt(),
                file.getUpdatedAt());
    }
}
