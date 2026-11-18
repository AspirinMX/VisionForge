package app.pagefault.visionforge.billing.credit;

import java.time.Instant;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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

    @Update("""
            UPDATE credit_account
            SET balance_units = balance_units + #{deltaUnits},
                version = version + 1,
                updated_at = #{now}
            WHERE user_id = #{userId}
            """)
    int addBalance(
            @Param("userId") UUID userId,
            @Param("deltaUnits") long deltaUnits,
            @Param("now") Instant now);

    @Update("""
            UPDATE credit_account
            SET balance_units = balance_units - #{deductUnits},
                version = version + 1,
                updated_at = #{now}
            WHERE user_id = #{userId}
              AND balance_units - reserved_units >= #{deductUnits}
            """)
    int deductAvailable(
            @Param("userId") UUID userId,
            @Param("deductUnits") long deductUnits,
            @Param("now") Instant now);
}
