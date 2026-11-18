package app.pagefault.visionforge.agent.storyboard;

import java.util.List;

public record StoryboardShot(
        String id,
        int order,
        String sceneId,
        int durationSeconds,
        String visual,
        String firstFramePrompt,
        String videoPrompt,
        StoryboardShotSize shotSize,
        StoryboardCamera camera,
        String dialogue,
        String voiceOver,
        String sound,
        List<StoryboardReference> references,
        StoryboardAssetRef firstFrame,
        List<StoryboardVideoCandidate> videoCandidates,
        StoryboardVideoSelection selectedVideo,
        StoryboardRange usedRange,
        StoryboardShotStatus status) {

    public StoryboardShot {
        references = references == null ? List.of() : List.copyOf(references);
        videoCandidates = videoCandidates == null ? List.of() : List.copyOf(videoCandidates);
    }
}
