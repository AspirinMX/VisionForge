package app.pagefault.visionforge.api.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdjustCreditRequest(
        @NotNull(message = "调账额度不能为空")
        Long deltaUnits,

        @NotBlank(message = "幂等键不能为空")
        @Size(max = 128, message = "幂等键长度不能超过 128 个字符")
        String idempotencyKey,

        @Size(max = 500, message = "备注长度不能超过 500 个字符")
        String remark) {
}
