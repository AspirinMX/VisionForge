package app.pagefault.visionforge.api.account.dto;

import java.util.UUID;

import app.pagefault.visionforge.billing.credit.RedemptionService;

public record RedeemCodeResponse(
        UUID ledgerId,
        String code,
        long amountUnits,
        long newBalanceUnits,
        long newAvailableUnits) {

    public static RedeemCodeResponse from(RedemptionService.RedeemResult result) {
        return new RedeemCodeResponse(
                result.ledgerId(),
                result.code(),
                result.amountUnits(),
                result.newBalanceUnits(),
                result.newAvailableUnits());
    }
}
