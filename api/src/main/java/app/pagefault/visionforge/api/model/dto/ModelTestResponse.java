package app.pagefault.visionforge.api.model.dto;

import app.pagefault.visionforge.agent.model.ModelConnectivityService;

public record ModelTestResponse(
        boolean ok,
        boolean reachable,
        Integer statusCode,
        long latencyMs,
        String protocol,
        String detail) {

    public static ModelTestResponse from(ModelConnectivityService.ProbeResult result) {
        return new ModelTestResponse(
                result.ok(),
                result.reachable(),
                result.statusCode(),
                result.latencyMs(),
                result.protocol(),
                result.detail());
    }
}
