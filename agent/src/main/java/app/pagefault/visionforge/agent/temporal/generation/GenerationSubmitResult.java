package app.pagefault.visionforge.agent.temporal.generation;

/**
 * Small workflow-boundary result. Artifacts never cross this boundary; the submit/poll activity
 * archives them through the asset service before returning.
 */
public record GenerationSubmitResult(boolean pending, boolean finished) {
}
