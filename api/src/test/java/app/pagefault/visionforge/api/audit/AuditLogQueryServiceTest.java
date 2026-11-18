package app.pagefault.visionforge.api.audit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import app.pagefault.visionforge.common.api.PageResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class AuditLogQueryServiceTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(AuditLogEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AuditLogEntity.class);
        }
    }

    @Test
    void queryClampsPageSizeAndReturnsPagedEntities() {
        AuditLogMapper mapper = Mockito.mock(AuditLogMapper.class);
        AuditLogQueryService service = new AuditLogQueryService(mapper);

        AuditLogEntity row = sampleRow("IDENTITY_USER", "ROLE_CHANGE", "=evil");
        Page<AuditLogEntity> page = new Page<>(1, 200, 1);
        page.setRecords(List.of(row));
        when(mapper.selectPage(any(), any())).thenReturn(page);

        PageResult<AuditLogEntity> result = service.query(
                0, 999, "IDENTITY_USER", "ROLE_CHANGE", row.getOperatorId(), "biz-1", "trace-1", true, null, null);

        assertThat(result.page()).isEqualTo(1L);
        assertThat(result.size()).isEqualTo(200L);
        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void exportCsvNeutralizesFormulaCells() {
        AuditLogMapper mapper = Mockito.mock(AuditLogMapper.class);
        AuditLogQueryService service = new AuditLogQueryService(mapper);

        AuditLogEntity row = sampleRow("BILLING", "ADJUST", "=SUM(1,2)");
        Page<AuditLogEntity> page = new Page<>(1, 200, 1);
        page.setRecords(List.of(row));
        when(mapper.selectPage(any(), any())).thenReturn(page);

        byte[] bytes = service.exportCsv(null, null, null, null, null, null, null, null, 100);
        String csv = new String(bytes, StandardCharsets.UTF_8);

        assertThat(csv).startsWith("\uFEFFid,type,subType,bizNo,operatorId,operatorName,success,detail,traceId,errorMessage,createdAt");
        assertThat(csv).contains("'=SUM(1,2)");
    }

    private AuditLogEntity sampleRow(String type, String subType, String detail) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setId(UUID.fromString("0193b2c4-7f2a-7c3e-9d10-000000000010"));
        entity.setType(type);
        entity.setSubType(subType);
        entity.setBizNo("biz-1");
        entity.setOperatorId(UUID.fromString("0193b2c4-7f2a-7c3e-9d10-000000000001"));
        entity.setOperatorName("admin@example.com");
        entity.setSuccess(true);
        entity.setDetail(detail);
        entity.setTraceId("trace-1");
        entity.setCreatedAt(Instant.parse("2026-11-19T00:00:00Z"));
        return entity;
    }
}
