package app.pagefault.visionforge.api.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import app.pagefault.visionforge.common.api.PageResult;
import app.pagefault.visionforge.common.util.CsvWriter;

import lombok.RequiredArgsConstructor;

/**
 * Read-only query and CSV export service for {@code audit_log} records.
 */
@Service
@RequiredArgsConstructor
public class AuditLogQueryService {

    private static final long MAX_PAGE_SIZE = 200;
    private static final int EXPORT_CHUNK_SIZE = 200;
    private static final int MAX_EXPORT_ROWS = 10_000;

    private final AuditLogMapper auditLogMapper;

    public PageResult<AuditLogEntity> query(
            long page,
            long size,
            String type,
            String subType,
            UUID operatorId,
            String bizNo,
            String traceId,
            Boolean success,
            Instant from,
            Instant to) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        LambdaQueryWrapper<AuditLogEntity> wrapper = buildFilter(
                type, subType, operatorId, bizNo, traceId, success, from, to);
        Page<AuditLogEntity> result = auditLogMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        return PageResult.of(result.getRecords(), result.getCurrent(), result.getSize(), result.getTotal());
    }

    public byte[] exportCsv(
            String type,
            String subType,
            UUID operatorId,
            String bizNo,
            String traceId,
            Boolean success,
            Instant from,
            Instant to,
            int maxRows) {
        int safeMaxRows = Math.min(Math.max(maxRows, 1), MAX_EXPORT_ROWS);
        List<AuditLogEntity> rows = collectForExport(
                buildFilter(type, subType, operatorId, bizNo, traceId, success, from, to),
                safeMaxRows);

        CsvWriter csv = new CsvWriter()
                .addHeader("id", "type", "subType", "bizNo", "operatorId", "operatorName",
                        "success", "detail", "traceId", "errorMessage", "createdAt");
        for (AuditLogEntity row : rows) {
            csv.addTextCell(row.getId())
                    .addTextCell(row.getType())
                    .addTextCell(row.getSubType())
                    .addTextCell(row.getBizNo())
                    .addTextCell(row.getOperatorId())
                    .addTextCell(row.getOperatorName())
                    .addBooleanCell(row.isSuccess())
                    .addTextCell(row.getDetail())
                    .addTextCell(row.getTraceId())
                    .addTextCell(row.getErrorMessage())
                    .addTextCell(row.getCreatedAt())
                    .endRow();
        }
        return csv.toUtf8Bytes();
    }

    private List<AuditLogEntity> collectForExport(LambdaQueryWrapper<AuditLogEntity> wrapper, int maxRows) {
        List<AuditLogEntity> collected = new ArrayList<>();
        long currentPage = 1;
        while (collected.size() < maxRows) {
            long remaining = maxRows - collected.size();
            long fetchSize = Math.min(EXPORT_CHUNK_SIZE, remaining);
            Page<AuditLogEntity> page = auditLogMapper.selectPage(new Page<>(currentPage, fetchSize), wrapper);
            List<AuditLogEntity> records = page.getRecords();
            if (records == null || records.isEmpty()) {
                break;
            }
            collected.addAll(records);
            if (records.size() < fetchSize) {
                break;
            }
            currentPage++;
        }
        return collected;
    }

    private LambdaQueryWrapper<AuditLogEntity> buildFilter(
            String type,
            String subType,
            UUID operatorId,
            String bizNo,
            String traceId,
            Boolean success,
            Instant from,
            Instant to) {
        LambdaQueryWrapper<AuditLogEntity> wrapper = Wrappers.<AuditLogEntity>lambdaQuery()
                .orderByDesc(AuditLogEntity::getCreatedAt)
                .orderByDesc(AuditLogEntity::getId);
        if (hasText(type)) {
            wrapper.eq(AuditLogEntity::getType, type.trim());
        }
        if (hasText(subType)) {
            wrapper.eq(AuditLogEntity::getSubType, subType.trim());
        }
        if (operatorId != null) {
            wrapper.eq(AuditLogEntity::getOperatorId, operatorId);
        }
        if (hasText(bizNo)) {
            wrapper.like(AuditLogEntity::getBizNo, bizNo.trim());
        }
        if (hasText(traceId)) {
            wrapper.eq(AuditLogEntity::getTraceId, traceId.trim());
        }
        if (success != null) {
            wrapper.eq(AuditLogEntity::isSuccess, success);
        }
        if (from != null) {
            wrapper.ge(AuditLogEntity::getCreatedAt, from);
        }
        if (to != null) {
            wrapper.le(AuditLogEntity::getCreatedAt, to);
        }
        return wrapper;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
