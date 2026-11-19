package app.pagefault.visionforge.agent.conversation;

import java.util.List;
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

    /**
     * Returns conversations in the same order the console renders them: most recent activity
     * first, with id as a stable tie-breaker.
     */
    @Select("""
            SELECT *
              FROM conversation
             WHERE created_by = #{userId}
               AND deleted_at IS NULL
             ORDER BY COALESCE(last_message_at, created_at) DESC, id DESC
            """)
    List<Conversation> selectByCreator(@Param("userId") UUID userId);

    /**
     * Finds a conversation that has never been used: no messages, no workspace files, no assets
     * and no generation/composition tasks. A blank conversation with a non-empty workspace is
     * deliberately not reusable.
     */
    @Select("""
            SELECT c.*
              FROM conversation c
             WHERE c.created_by = #{userId}
               AND c.deleted_at IS NULL
               AND c.last_message_at IS NULL
               AND NOT EXISTS (
                   SELECT 1 FROM message m WHERE m.conversation_id = c.id)
               AND NOT EXISTS (
                   SELECT 1 FROM conversation_file f
                    WHERE f.conversation_id = c.id AND f.deleted_at IS NULL)
               AND NOT EXISTS (
                   SELECT 1 FROM asset a
                    WHERE a.conversation_id = c.id AND a.deleted_at IS NULL)
               AND NOT EXISTS (
                   SELECT 1 FROM generation_task g WHERE g.conversation_id = c.id)
               AND NOT EXISTS (
                   SELECT 1 FROM composition_task t WHERE t.conversation_id = c.id)
             ORDER BY c.created_at DESC, c.id DESC
             LIMIT 1
            """)
    Conversation findReusableBlank(@Param("userId") UUID userId);

    /**
     * Serializes blank-conversation reuse per user so two tabs cannot create duplicates.
     */
    @Select("SELECT id FROM user_account WHERE id = #{userId} FOR UPDATE")
    UUID lockUser(@Param("userId") UUID userId);
}
