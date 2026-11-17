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
@TableName("message")
public class Message {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID conversationId;

    private UUID senderUserId;

    private MessageRole role;

    private String content;

    private long sequenceNo;

    private MessageStatus status;

    private Instant createdAt;
}
