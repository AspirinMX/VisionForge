package app.pagefault.visionforge.agent.tool;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("tool_invocation")
public class ToolInvocation {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID agentRunId;

    private UUID conversationId;

    private String toolCallId;

    private String toolName;

    private String arguments;

    private ToolInvocationStatus status;

    private String result;

    private String errorCode;

    private String errorMessage;

    private Instant createdAt;

    private Instant completedAt;
}
