package app.pagefault.visionforge.api.identity.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record TransferOwnershipRequest(
        @NotNull(message = "userId 不能为空")
        UUID userId) {
}
