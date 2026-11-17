package app.pagefault.visionforge.identity.auth;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("refresh_token")
public class RefreshToken {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID userId;

    private String tokenHash;

    private Instant issuedAt;

    private Instant expiresAt;

    private Instant revokedAt;

    private UUID replacedByTokenId;
}
