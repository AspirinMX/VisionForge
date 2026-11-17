package app.pagefault.visionforge.api.audit;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("audit_log")
public class AuditLogEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private String type;

    private String subType;

    private String bizNo;

    private UUID operatorId;

    private String operatorName;

    private UUID workspaceId;

    private boolean success;

    private String detail;

    private String extra;

    private String traceId;

    private String errorMessage;

    private Instant createdAt;
}
