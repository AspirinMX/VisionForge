package app.pagefault.visionforge.agent.conversation;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Non-text part of a message. Asset references are the durable link between a user's message and
 * the images the main model should see as {@code input_image} for that turn.
 */
@Getter
@Setter
@TableName("message_part")
public class MessagePart {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID messageId;

    private MessagePartType partType;

    private int ordinal;

    private String content;

    private UUID assetId;

    private UUID generationTaskId;

    private Instant createdAt;
}
