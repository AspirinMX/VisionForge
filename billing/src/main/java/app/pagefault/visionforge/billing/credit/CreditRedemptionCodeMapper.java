package app.pagefault.visionforge.billing.credit;

import java.time.Instant;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface CreditRedemptionCodeMapper extends BaseMapper<CreditRedemptionCode> {

    @Select("SELECT * FROM credit_redemption_code WHERE code = #{code} FOR UPDATE")
    CreditRedemptionCode selectByCodeForUpdate(@Param("code") String code);

    @Update("""
            UPDATE credit_redemption_code
            SET redeemed_count = redeemed_count + 1,
                updated_at = #{now}
            WHERE id = #{id}
              AND status = 'ACTIVE'
              AND redeemed_count < max_uses
              AND (expires_at IS NULL OR expires_at > #{now})
            """)
    int incrementRedeemedCountIfAvailable(
            @Param("id") UUID id,
            @Param("now") Instant now);
}
