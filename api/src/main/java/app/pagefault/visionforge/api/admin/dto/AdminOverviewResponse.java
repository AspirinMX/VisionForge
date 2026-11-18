package app.pagefault.visionforge.api.admin.dto;

public record AdminOverviewResponse(
        long totalUsers,
        long activeUsers,
        long disabledUsers,
        long activeConversations,
        long enabledModels,
        long activeAgentRuns,
        long failedAgentRuns,
        long activeGenerationTasks,
        long archiveFailedGenerationTasks,
        long pendingReconciliationTasks,
        long activeCompositionTasks,
        long failedCompositionTasks,
        long failedWorkflowStarts) {
}
