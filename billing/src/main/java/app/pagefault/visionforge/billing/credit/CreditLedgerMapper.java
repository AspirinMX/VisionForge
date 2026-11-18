package app.pagefault.visionforge.billing.credit;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface CreditLedgerMapper extends BaseMapper<CreditLedger> {

    @Insert("""
            INSERT INTO credit_ledger
                (id, user_id, reservation_id, entry_type, amount_units, business_key, remark, created_at)
            VALUES (#{id}, #{userId}, #{reservationId}, #{entryType}, #{amountUnits},
                    #{businessKey}, #{remark}, #{createdAt})
            ON CONFLICT (business_key) DO NOTHING
            """)
    int insertIfAbsent(CreditLedger ledger);
}
