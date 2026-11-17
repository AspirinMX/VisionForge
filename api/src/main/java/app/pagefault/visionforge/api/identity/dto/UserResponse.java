package app.pagefault.visionforge.api.identity.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.identity.user.UserAccount;
import app.pagefault.visionforge.identity.user.UserRole;
import app.pagefault.visionforge.identity.user.UserStatus;

public record UserResponse(
        UUID id,
        String email,
        String displayName,
        UserRole role,
        UserStatus status,
        Instant createdAt) {

    public static UserResponse from(UserAccount user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt());
    }
}
