package app.pagefault.visionforge.agent.generation;

import java.util.List;

/**
 * Aggregate of the caller's most recent media generation tasks. Only tasks inside the requested
 * window are counted; the total is the sum of all buckets.
 */
public record GenerationUsageSummary(
        int window,
        long totalPriceUnits,
        List<GenerationUsageBucket> buckets) {
}
