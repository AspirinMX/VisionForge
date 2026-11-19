package app.pagefault.visionforge.api.admin.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Aggregated read-only metrics for the admin operations dashboard.
 *
 * <p>Credits only reflect settled media generation charges. Chat token usage is measured
 * but is not billed by the current billing pipeline.</p>
 */
public record AdminDashboardResponse(
        int rangeDays,
        Instant generatedAt,
        Totals totals,
        Deltas deltas,
        List<DailyPoint> daily,
        List<LeaderboardEntry> topUsersByCredits,
        List<LeaderboardEntry> topUsersByTokens,
        Attention attention,
        Live live) {

    public record Totals(
            long settledCredits,
            long todayCredits,
            long inputTokens,
            long outputTokens,
            Double successRate,
            long activeUsers,
            long newUsers) {
    }

    /**
     * Relative deltas are fractions (0.12 = +12%). Success rate delta is expressed
     * in percentage points (0.004 = +0.4pp).
     */
    public record Deltas(
            Double settledCredits,
            Double totalTokens,
            Double activeUsers,
            Double successRatePoints) {
    }

    public record DailyPoint(
            LocalDate date,
            long inputTokens,
            long outputTokens,
            long invocations,
            long settledCredits,
            long succeeded,
            long failed,
            long cancelled) {
    }

    public record LeaderboardEntry(
            UUID userId,
            String displayName,
            long value) {
    }

    public record Attention(
            long archiveFailed,
            long pendingReconciliation,
            long failedWorkflowStarts,
            long failedTasks24h,
            long failedCompositionTasks) {
    }

    public record Live(
            long activeAgentRuns,
            long activeGenerationTasks,
            long activeCompositionTasks) {
    }
}
