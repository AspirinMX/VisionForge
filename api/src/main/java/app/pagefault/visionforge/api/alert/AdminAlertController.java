package app.pagefault.visionforge.api.alert;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.agent.alert.AlertChannel;
import app.pagefault.visionforge.agent.alert.AlertChannelService;
import app.pagefault.visionforge.agent.alert.AlertDelivery;
import app.pagefault.visionforge.agent.alert.AlertDeliveryStatus;
import app.pagefault.visionforge.agent.alert.AlertDispatchService;
import app.pagefault.visionforge.api.alert.dto.AlertChannelResponse;
import app.pagefault.visionforge.api.alert.dto.AlertDeliveryResponse;
import app.pagefault.visionforge.api.alert.dto.CreateAlertChannelRequest;
import app.pagefault.visionforge.api.alert.dto.UpdateAlertChannelRequest;
import app.pagefault.visionforge.api.identity.AuthenticatedUser;
import app.pagefault.visionforge.common.api.ApiResponse;
import app.pagefault.visionforge.common.api.PageResult;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/alerts")
@RequiredArgsConstructor
public class AdminAlertController {

    private final AlertChannelService channelService;
    private final AlertDispatchService dispatchService;

    @GetMapping("/channels")
    public ApiResponse<List<AlertChannelResponse>> listChannels() {
        return ApiResponse.success(channelService.listChannels().stream()
                .map(AlertChannelResponse::from)
                .toList());
    }

    @PostMapping("/channels")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AlertChannelResponse> createChannel(
            @Valid @RequestBody CreateAlertChannelRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        AlertChannel created = channelService.createChannel(
                AuthenticatedUser.id(jwt),
                new AlertChannelService.ChannelInput(
                        request.name(),
                        request.channelType(),
                        request.target(),
                        request.secret(),
                        request.subscribedEvents(),
                        request.minSeverity(),
                        request.enabled()));
        return ApiResponse.success(AlertChannelResponse.from(created));
    }

    @PatchMapping("/channels/{id}")
    public ApiResponse<AlertChannelResponse> updateChannel(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAlertChannelRequest request) {
        AlertChannel updated = channelService.updateChannel(
                id,
                new AlertChannelService.ChannelInput(
                        request.name(),
                        request.channelType(),
                        request.target(),
                        request.secret(),
                        request.subscribedEvents(),
                        request.minSeverity(),
                        request.enabled()));
        return ApiResponse.success(AlertChannelResponse.from(updated));
    }

    @DeleteMapping("/channels/{id}")
    public ApiResponse<Void> deleteChannel(@PathVariable UUID id) {
        channelService.deleteChannel(id);
        return ApiResponse.success();
    }

    @PostMapping("/channels/{id}/test")
    public ApiResponse<AlertDeliveryResponse> testChannel(@PathVariable UUID id) {
        AlertDelivery delivery = dispatchService.testChannel(id);
        return ApiResponse.success(AlertDeliveryResponse.from(delivery));
    }

    @GetMapping("/deliveries")
    public ApiResponse<PageResult<AlertDeliveryResponse>> listDeliveries(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") long size,
            @RequestParam(required = false) UUID channelId,
            @RequestParam(required = false) AlertDeliveryStatus status,
            @RequestParam(required = false) String eventType) {
        PageResult<AlertDelivery> result = channelService.listDeliveries(page, size, channelId, status, eventType);
        return ApiResponse.success(PageResult.of(
                result.items().stream().map(AlertDeliveryResponse::from).toList(),
                result.page(),
                result.size(),
                result.total()));
    }
}
