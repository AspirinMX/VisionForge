package app.pagefault.visionforge.api.account.dto;

import java.util.List;

import app.pagefault.visionforge.agent.generation.GenerationBillingStatus;
import app.pagefault.visionforge.agent.generation.GenerationUsageSummary;

/**
 * {@code priceUnits} summary of the caller's newest tasks, grouped by billing status.
 */
public record CreditUsageResponse(
        int window,
        long totalPriceUnits,
        List<Bucket> buckets) {

    public record Bucket(
            GenerationBillingStatus billingStatus,
            long taskCount,
            long priceUnits) {
    }

    public static CreditUsageResponse from(GenerationUsageSummary summary) {
        return new CreditUsageResponse(
                summary.window(),
                summary.totalPriceUnits(),
                summary.buckets().stream()
                        .map(bucket -> new Bucket(
                                bucket.getBillingStatus(),
                                bucket.getTaskCount(),
                                bucket.getPriceUnits()))
                        .toList());
    }
}
