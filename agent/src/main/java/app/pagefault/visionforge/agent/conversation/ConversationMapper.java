package app.pagefault.visionforge.agent.conversation;

import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    /**
     * Serializes message allocation and run creation for one conversation. Callers must already be
     * inside a transaction; the lock is released when it commits or rolls back.
     */
    @Select("SELECT * FROM conversation WHERE id = #{id} FOR UPDATE")
    Conversation selectForUpdate(@Param("id") UUID id);
}
