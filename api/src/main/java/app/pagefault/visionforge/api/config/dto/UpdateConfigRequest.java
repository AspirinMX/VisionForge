package app.pagefault.visionforge.api.config.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateConfigRequest(
        @NotBlank(message = "配置内容不能为空")
        @Size(max = 65536, message = "配置内容过长")
        String content) {
}
