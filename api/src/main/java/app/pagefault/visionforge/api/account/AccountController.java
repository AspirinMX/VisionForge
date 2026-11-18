package app.pagefault.visionforge.api.account;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.agent.generation.GenerationTask;
import app.pagefault.visionforge.agent.generation.GenerationTaskService;
import app.pagefault.visionforge.api.account.dto.CreditLedgerEntryResponse;
import app.pagefault.visionforge.api.account.dto.CreditSummaryResponse;
import app.pagefault.visionforge.api.account.dto.CreditUsageResponse;
import app.pagefault.visionforge.api.account.dto.RedeemCodeRequest;
import app.pagefault.visionforge.api.account.dto.RedeemCodeResponse;
import app.pagefault.visionforge.api.identity.AuthenticatedUser;
import app.pagefault.visionforge.billing.credit.CreditLedger;
import app.pagefault.visionforge.billing.credit.CreditReservation;
import app.pagefault.visionforge.billing.credit.CreditService;
import app.pagefault.visionforge.billing.credit.RedemptionService;
import app.pagefault.visionforge.common.api.ApiResponse;
import app.pagefault.visionforge.common.util.CsvWriter;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Caller credit account summary, ledger, CSV export, and CDK redemption endpoints.
 */
@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
public class AccountController {

    private static final MediaType TEXT_CSV_UTF8 = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final CreditService creditService;
    private final RedemptionService redemptionService;
    private final GenerationTaskService generationTaskService;

    @GetMapping("/credit")
    public ApiResponse<CreditSummaryResponse> credit(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(
                CreditSummaryResponse.from(creditService.account(AuthenticatedUser.id(jwt))));
    }

    /**
     * Newest-first ledger page. {@code before} is the id of the last row of the previous page and
     * acts as a keyset cursor because ledger ids are UUIDv7.
     */
    @GetMapping("/credit/ledger")
    public ApiResponse<List<CreditLedgerEntryResponse>> ledger(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) UUID before,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = AuthenticatedUser.id(jwt);
        List<CreditLedger> entries = creditService.recentEntries(userId, limit, before);
        if (entries.isEmpty()) {
            return ApiResponse.success(List.of());
        }
        Map<UUID, CreditReservation> reservations = creditService.reservations(
                userId,
                entries.stream()
                        .map(CreditLedger::getReservationId)
                        .filter(Objects::nonNull)
                        .toList());
        Map<UUID, GenerationTask> tasks = generationTaskService.findByIds(
                userId,
                reservations.values().stream()
                        .map(CreditReservation::getTaskId)
                        .filter(Objects::nonNull)
                        .toList());
        return ApiResponse.success(entries.stream()
                .map(entry -> toResponse(entry, reservations, tasks))
                .toList());
    }

    @GetMapping("/credit/ledger/export")
    public ResponseEntity<byte[]> exportLedger(
            @RequestParam(defaultValue = "2000") int maxRows,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = AuthenticatedUser.id(jwt);
        List<CreditLedger> entries = creditService.exportUserEntries(userId, maxRows);
        CsvWriter csv = new CsvWriter()
                .addHeader("id", "entryType", "amountUnits", "reservationId", "remark", "createdAt");
        for (CreditLedger entry : entries) {
            csv.addTextCell(entry.getId())
                    .addTextCell(entry.getEntryType())
                    .addNumberCell(entry.getAmountUnits())
                    .addTextCell(entry.getReservationId())
                    .addTextCell(entry.getRemark())
                    .addTextCell(entry.getCreatedAt())
                    .endRow();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(TEXT_CSV_UTF8);
        headers.setContentDisposition(ContentDisposition.attachment().filename("credit-ledger.csv").build());
        return ResponseEntity.ok().headers(headers).body(csv.toUtf8Bytes());
    }

    @PostMapping("/credit/redeem")
    public ApiResponse<RedeemCodeResponse> redeem(
            @Valid @RequestBody RedeemCodeRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        RedemptionService.RedeemResult result = redemptionService.redeem(
                AuthenticatedUser.id(jwt), request.code());
        return ApiResponse.success(RedeemCodeResponse.from(result));
    }

    @GetMapping("/usage")
    public ApiResponse<CreditUsageResponse> usage(
            @RequestParam(defaultValue = "100") int window,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(CreditUsageResponse.from(generationTaskService.usageSummary(
                AuthenticatedUser.id(jwt), window)));
    }

    private CreditLedgerEntryResponse toResponse(
            CreditLedger entry,
            Map<UUID, CreditReservation> reservations,
            Map<UUID, GenerationTask> tasks) {
        CreditReservation reservation = entry.getReservationId() == null
                ? null
                : reservations.get(entry.getReservationId());
        GenerationTask task = reservation == null ? null : tasks.get(reservation.getTaskId());
        return new CreditLedgerEntryResponse(
                entry.getId(),
                entry.getEntryType(),
                entry.getAmountUnits(),
                entry.getReservationId(),
                reservation == null ? null : reservation.getTaskId(),
                task == null ? null : task.getType(),
                task == null ? null : task.getStatus(),
                entry.getRemark(),
                entry.getCreatedAt());
    }
}
