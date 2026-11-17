package app.pagefault.visionforge.asset.library;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * A file in a conversation's own work area. Files belong to exactly one conversation and are never
 * shared across conversations or users.
 */
@Getter
@Setter
@TableName("conversation_file")
public class ConversationFile {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID conversationId;

    private UUID userId;

    private ConversationFileKind kind;

    private String title;

    private String path;

    private String mimeType;

    private ConversationFileStorageKind storageKind;

    private int currentVersion;

    private ConversationFileStatus status;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant deletedAt;
}
