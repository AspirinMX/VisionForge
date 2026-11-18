package app.pagefault.visionforge.agent.storyboard;

import java.util.UUID;

public record StoryboardAssetRef(UUID assetId, UUID fileId, int version) {
}
