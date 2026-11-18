package app.pagefault.visionforge.agent.storyboard;

import java.util.List;

public record StoryboardDocument(
        int schemaVersion,
        String title,
        String aspectRatio,
        String language,
        String style,
        List<StoryboardShot> shots) {

    public StoryboardDocument {
        shots = shots == null ? List.of() : List.copyOf(shots);
    }
}
