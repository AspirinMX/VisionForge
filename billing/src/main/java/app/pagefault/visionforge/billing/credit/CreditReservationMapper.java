package app.pagefault.visionforge.billing.credit;

import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface CreditReservationMapper extends BaseMapper<CreditReservation> {

    @Insert("""
            INSERT INTO credit_reservation
                (id, user_id, business_key, task_id, amount_units, status, created_at)
            VALUES (#{id}, #{userId}, #{businessKey}, #{taskId}, #{amountUnits},
                    #{status}, #{createdAt})
            ON CONFLICT (user_id, business_key) DO NOTHING
            """)
    int insertIfAbsent(CreditReservation reservation);
}
