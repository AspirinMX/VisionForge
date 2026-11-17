package app.pagefault.visionforge.identity.user;

import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface UserMapper extends BaseMapper<UserAccount> {
}
