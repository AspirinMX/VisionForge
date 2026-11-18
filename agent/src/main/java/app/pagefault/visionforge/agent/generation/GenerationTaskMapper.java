package app.pagefault.visionforge.agent.generation;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface GenerationTaskMapper extends BaseMapper<GenerationTask> {

    /**
     * Aggregates {@code priceUnits} of the caller's newest tasks by billing status. The inner
     * ordering plus LIMIT keeps the summary bounded even for heavy accounts.
     */
    @Select("""
            SELECT recent.billing_status AS billingStatus,
                   COUNT(*)              AS taskCount,
                   COALESCE(SUM(recent.price_units), 0) AS priceUnits
            FROM (
                SELECT billing_status, price_units
                FROM generation_task
                WHERE user_id = #{userId}
                ORDER BY created_at DESC
                LIMIT #{window}
            ) recent
            GROUP BY recent.billing_status
            ORDER BY recent.billing_status
            """)
    List<GenerationUsageBucket> summarizeUsage(
            @Param("userId") UUID userId, @Param("window") int window);
}
