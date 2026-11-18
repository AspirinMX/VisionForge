package app.pagefault.visionforge.api.billing.dto;

import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateRedemptionBatchRequest(
        @NotBlank(message = "批次名称不能为空")
        @Size(max = 100, message = "批次名称最多 100 个字符")
        String batchName,

        @Min(value = 1, message = "单次生成数量至少为 1")
        @Max(value = 100, message = "单次生成数量最多为 100")
        int count,

        @NotNull(message = "面额不能为空")
        @Min(value = 1, message = "面额必须大于 0")
        Long amountUnits,

        @Min(value = 1, message = "单码最大兑换次数至少为 1")
        @Max(value = 100000, message = "单码最大兑换次数最多为 100000")
        int maxUsesPerCode,

        @Min(value = 1, message = "每用户限兑次数至少为 1")
        int perUserLimit,

        @Size(max = 12, message = "前缀长度不能超过 12 个字符")
        String customPrefix,

        Instant expiresAt) {
}
