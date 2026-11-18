package app.pagefault.visionforge.billing.credit;

import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface CreditRedemptionRecordMapper extends BaseMapper<CreditRedemptionRecord> {

    @Select("SELECT COUNT(*) FROM credit_redemption_record WHERE code_id = #{codeId} AND user_id = #{userId}")
    int countByCodeAndUser(@Param("codeId") UUID codeId, @Param("userId") UUID userId);
}
