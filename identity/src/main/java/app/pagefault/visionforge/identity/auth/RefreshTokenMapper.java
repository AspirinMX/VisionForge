package app.pagefault.visionforge.identity.auth;

import java.time.Instant;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshToken> {

    @Update("UPDATE refresh_token SET revoked_at = #{revokedAt} WHERE user_id = #{userId} AND revoked_at IS NULL")
    int revokeAllActiveByUserId(@Param("userId") UUID userId, @Param("revokedAt") Instant revokedAt);
}
