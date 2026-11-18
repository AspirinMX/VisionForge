package app.pagefault.visionforge.agent.composition;

import java.util.UUID;

public record SubmitCompositionCommand(
        UUID conversationId,
        UUID storyboardFileId,
        Integer storyboardVersion,
        String outputSpec) {
}
