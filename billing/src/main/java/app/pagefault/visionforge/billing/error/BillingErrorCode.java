package app.pagefault.visionforge.billing.error;

import app.pagefault.visionforge.common.error.ErrorCode;

public enum BillingErrorCode implements ErrorCode {

    ACCOUNT_NOT_FOUND("BILLING_ACCOUNT_NOT_FOUND", 404, "额度账户不存在"),
    INSUFFICIENT_CREDIT("BILLING_INSUFFICIENT_CREDIT", 409, "额度不足，无法受理该任务"),
    RESERVATION_NOT_FOUND("BILLING_RESERVATION_NOT_FOUND", 404, "额度预占记录不存在"),
    RESERVATION_STATE_CONFLICT("BILLING_RESERVATION_STATE_CONFLICT", 409, "额度预占状态不允许该操作"),
    SETTLE_CONFLICT("BILLING_SETTLE_CONFLICT", 409, "额度结算失败，账户余额与预占不一致"),
    AMOUNT_INVALID("BILLING_AMOUNT_INVALID", 400, "额度金额不能为负数");

    private final String code;
    private final int httpStatus;
    private final String message;

    BillingErrorCode(String code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String message() {
        return message;
    }
}
