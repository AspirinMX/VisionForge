package app.pagefault.visionforge.api.audit;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {
}
