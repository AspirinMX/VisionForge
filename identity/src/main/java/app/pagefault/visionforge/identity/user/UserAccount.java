package app.pagefault.visionforge.identity.user;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("user_account")
public class UserAccount {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private String email;

    private String displayName;

    private String passwordHash;

    private UserStatus status;

    private UserRole role;

    private Instant createdAt;

    private Instant updatedAt;
}
