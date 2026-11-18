package app.pagefault.visionforge.agent.storyboard;

import java.util.UUID;

public record StoryboardVideoSelection(
        UUID taskId,
        UUID assetId,
        UUID fileId,
        int version) {
}
