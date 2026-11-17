package app.pagefault.visionforge.asset.library;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("conversation_item")
public class ConversationItem {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID workspaceId;

    private UUID conversationId;

    private UUID itemId;

    private ConversationItemRole role;

    private Integer pinnedVersion;

    private UUID addedBy;

    private Instant addedAt;
}
