package app.pagefault.visionforge.identity.auth;

import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshToken> {
}
