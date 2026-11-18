package app.pagefault.visionforge.api.billing;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.api.billing.dto.AdjustCreditRequest;
import app.pagefault.visionforge.api.billing.dto.AdminCreditLedgerResponse;
import app.pagefault.visionforge.api.billing.dto.CreateRedemptionBatchRequest;
import app.pagefault.visionforge.api.billing.dto.RedemptionCodeResponse;
import app.pagefault.visionforge.api.identity.AuthenticatedUser;
import app.pagefault.visionforge.billing.credit.CreditLedger;
import app.pagefault.visionforge.billing.credit.CreditLedgerEntryType;
import app.pagefault.visionforge.billing.credit.CreditRedemptionCode;
import app.pagefault.visionforge.billing.credit.CreditService;
import app.pagefault.visionforge.billing.credit.RedemptionCodeStatus;
import app.pagefault.visionforge.billing.credit.RedemptionService;
import app.pagefault.visionforge.common.api.ApiResponse;
import app.pagefault.visionforge.common.api.PageResult;
import app.pagefault.visionforge.identity.user.IdentityService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminBillingController {

    private final IdentityService identityService;
    private final CreditService creditService;
    private final RedemptionService redemptionService;

    @PostMapping("/users/{id}/credit-adjust")
    public ApiResponse<AdminCreditLedgerResponse> adjustCredit(
            @PathVariable UUID id,
            @Valid @RequestBody AdjustCreditRequest request) {
        identityService.getUser(id);
        CreditLedger ledger = creditService.adjustCredit(
                id, request.deltaUnits(), request.idempotencyKey(), request.remark());
        return ApiResponse.success(AdminCreditLedgerResponse.from(ledger));
    }

    @GetMapping("/billing/ledger")
    public ApiResponse<PageResult<AdminCreditLedgerResponse>> ledger(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") long size,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) CreditLedgerEntryType entryType) {
        PageResult<CreditLedger> result = creditService.listLedger(page, size, userId, entryType);
        return ApiResponse.success(PageResult.of(
                result.items().stream().map(AdminCreditLedgerResponse::from).toList(),
                result.page(),
                result.size(),
                result.total()));
    }

    @GetMapping("/billing/redemptions")
    public ApiResponse<PageResult<RedemptionCodeResponse>> listRedemptions(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") long size,
            @RequestParam(required = false) String batchName,
            @RequestParam(required = false) RedemptionCodeStatus status) {
        PageResult<CreditRedemptionCode> result = redemptionService.listCodes(page, size, batchName, status);
        return ApiResponse.success(PageResult.of(
                result.items().stream().map(RedemptionCodeResponse::from).toList(),
                result.page(),
                result.size(),
                result.total()));
    }

    @PostMapping("/billing/redemptions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<List<RedemptionCodeResponse>> createRedemptions(
            @Valid @RequestBody CreateRedemptionBatchRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        List<CreditRedemptionCode> codes = redemptionService.createBatch(
                AuthenticatedUser.id(jwt),
                request.batchName(),
                request.count(),
                request.amountUnits(),
                request.maxUsesPerCode(),
                request.perUserLimit(),
                request.customPrefix(),
                request.expiresAt());
        return ApiResponse.success(codes.stream().map(RedemptionCodeResponse::from).toList());
    }

    @PostMapping("/billing/redemptions/{id}/void")
    public ApiResponse<RedemptionCodeResponse> voidRedemption(@PathVariable UUID id) {
        return ApiResponse.success(RedemptionCodeResponse.from(redemptionService.voidCode(id)));
    }
}
