package app.pagefault.visionforge.api.asset.dto;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import app.pagefault.visionforge.asset.upload.UploadTicket;

public record UploadTicketResponse(
        UUID uploadSessionId,
        UUID assetId,
        URI uploadUrl,
        Instant uploadUrlExpiresAt,
        Instant sessionExpiresAt,
        Map<String, String> requiredHeaders) {

    public static UploadTicketResponse from(UploadTicket ticket) {
        return new UploadTicketResponse(
                ticket.uploadSessionId(),
                ticket.assetId(),
                ticket.uploadUrl(),
                ticket.uploadUrlExpiresAt(),
                ticket.sessionExpiresAt(),
                ticket.requiredHeaders());
    }
}
