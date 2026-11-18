package app.pagefault.visionforge.api.audit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.api.audit.dto.AuditLogResponse;
import app.pagefault.visionforge.common.api.ApiResponse;
import app.pagefault.visionforge.common.api.PageResult;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@RequiredArgsConstructor
public class AdminAuditLogController {

    private static final MediaType TEXT_CSV_UTF8 = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final AuditLogQueryService auditLogQueryService;

    @GetMapping
    public ApiResponse<PageResult<AuditLogResponse>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") long size,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String subType,
            @RequestParam(required = false) UUID operatorId,
            @RequestParam(required = false) String bizNo,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        PageResult<AuditLogEntity> result = auditLogQueryService.query(
                page, size, type, subType, operatorId, bizNo, traceId, success, from, to);
        return ApiResponse.success(PageResult.of(
                result.items().stream().map(AuditLogResponse::from).toList(),
                result.page(),
                result.size(),
                result.total()));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String subType,
            @RequestParam(required = false) UUID operatorId,
            @RequestParam(required = false) String bizNo,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "2000") int maxRows) {
        byte[] csv = auditLogQueryService.exportCsv(
                type, subType, operatorId, bizNo, traceId, success, from, to, maxRows);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(TEXT_CSV_UTF8);
        headers.setContentDisposition(ContentDisposition.attachment().filename("audit-logs.csv").build());
        return ResponseEntity.ok().headers(headers).body(csv);
    }
}
