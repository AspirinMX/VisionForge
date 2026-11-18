package app.pagefault.visionforge.api.account;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.agent.generation.GenerationTask;
import app.pagefault.visionforge.agent.generation.GenerationTaskService;
import app.pagefault.visionforge.api.account.dto.CreditLedgerEntryResponse;
import app.pagefault.visionforge.api.account.dto.CreditSummaryResponse;
import app.pagefault.visionforge.api.account.dto.CreditUsageResponse;
import app.pagefault.visionforge.api.identity.AuthenticatedUser;
import app.pagefault.visionforge.billing.credit.CreditLedger;
import app.pagefault.visionforge.billing.credit.CreditReservation;
import app.pagefault.visionforge.billing.credit.CreditService;
import app.pagefault.visionforge.common.api.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * Read-only view of the caller's credit account. All queries are scoped by the authenticated user;
 * a ledger page never exposes another account's reservations or tasks.
 */
@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
public class AccountController {

    private final CreditService creditService;
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
                entry.getCreatedAt());
    }
}
