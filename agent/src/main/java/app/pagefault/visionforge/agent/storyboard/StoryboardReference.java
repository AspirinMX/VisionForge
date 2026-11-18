package app.pagefault.visionforge.agent.storyboard;

import java.util.UUID;

public record StoryboardReference(UUID assetId, int version, String role) {
}
