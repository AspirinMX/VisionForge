package app.pagefault.visionforge.billing.credit;

import java.time.Instant;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface CreditAccountMapper extends BaseMapper<CreditAccount> {

    /**
     * Creates the account with its initial grant exactly once. ON CONFLICT keeps concurrent first
     * requests from aborting the surrounding transaction.
     */
    @Insert("""
            INSERT INTO credit_account
                (user_id, balance_units, reserved_units, version, created_at, updated_at)
            VALUES (#{userId}, #{balanceUnits}, 0, 0, #{now}, #{now})
            ON CONFLICT (user_id) DO NOTHING
            """)
    int insertIfAbsent(
            @Param("userId") UUID userId,
            @Param("balanceUnits") long balanceUnits,
            @Param("now") Instant now);
}
