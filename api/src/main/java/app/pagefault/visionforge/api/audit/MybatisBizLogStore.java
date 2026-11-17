package app.pagefault.visionforge.api.audit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import app.pagefault.visionforge.common.audit.BizLogRecord;
import app.pagefault.visionforge.common.audit.BizLogStore;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MybatisBizLogStore implements BizLogStore {

    private final AuditLogMapper auditLogMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(BizLogRecord record) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setId(record.id());
        entity.setType(record.type());
        entity.setSubType(record.subType());
        entity.setBizNo(record.bizNo());
        entity.setOperatorId(record.operatorId());
        entity.setOperatorName(record.operatorName());
        entity.setWorkspaceId(record.workspaceId());
        entity.setSuccess(record.success());
        entity.setDetail(record.detail());
        entity.setExtra(record.extra());
        entity.setTraceId(record.traceId());
        entity.setErrorMessage(record.errorMessage());
        entity.setCreatedAt(record.createdAt());
        auditLogMapper.insert(entity);
    }
}
