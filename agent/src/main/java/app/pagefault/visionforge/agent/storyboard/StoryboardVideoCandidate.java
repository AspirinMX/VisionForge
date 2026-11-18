package app.pagefault.visionforge.agent.storyboard;

import java.util.UUID;

public record StoryboardVideoCandidate(
        UUID taskId,
        UUID assetId,
        UUID fileId,
        int version,
        String status) {
}
