package app.pagefault.visionforge.agent.model;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("model_definition")
public class ModelDefinition {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private String displayName;

    private String baseUrl;

    private String apiKeyCiphertext;

    private String modelId;

    private ReasoningEffort reasoningEffort;

    private ReasoningReplayPolicy reasoningReplay;

    private int maxInputTokens;

    private int maxOutputTokens;

    private boolean enabled;

    private boolean defaultModel;

    private Instant createdAt;

    private Instant updatedAt;
}
