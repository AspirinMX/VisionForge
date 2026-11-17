package app.pagefault.visionforge.agent.conversation;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("conversation")
public class Conversation {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID workspaceId;

    private String title;

    private UUID createdBy;

    private UUID modelDefinitionId;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant lastMessageAt;
}
