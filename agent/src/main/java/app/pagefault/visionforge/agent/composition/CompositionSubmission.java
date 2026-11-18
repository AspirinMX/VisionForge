package app.pagefault.visionforge.agent.composition;

public record CompositionSubmission(
        CompositionTask task, String workflowId, boolean created) {
}
