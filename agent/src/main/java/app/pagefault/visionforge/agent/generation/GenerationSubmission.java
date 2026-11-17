package app.pagefault.visionforge.agent.generation;

public record GenerationSubmission(GenerationTask task, String workflowId, boolean created) {
}
