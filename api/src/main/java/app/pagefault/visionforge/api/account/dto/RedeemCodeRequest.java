package app.pagefault.visionforge.api.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RedeemCodeRequest(
        @NotBlank(message = "兑换码不能为空")
        @Size(max = 64, message = "兑换码长度不能超过 64 个字符")
        String code) {
}
