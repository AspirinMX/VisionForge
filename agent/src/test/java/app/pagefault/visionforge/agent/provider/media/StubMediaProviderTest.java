package app.pagefault.visionforge.agent.provider.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import app.pagefault.visionforge.agent.generation.GenerationType;
import app.pagefault.visionforge.agent.model.ExecutionMode;

class StubMediaProviderTest {

    private final StubMediaProvider provider = new StubMediaProvider();

    @Test
    void imageProviderProducesDecodablePng() {
        MediaProviderOutcome outcome = provider.start(request(GenerationType.IMAGE));

        assertThat(outcome).isInstanceOf(MediaProviderOutcome.Completed.class);
        MediaArtifact artifact = ((MediaProviderOutcome.Completed) outcome).artifacts().get(0);
        assertThat(artifact.mimeType()).isEqualTo("image/png");
        assertThat(artifact.content().length).isGreaterThan(100);
        assertThat(artifact.content()[0] & 0xFF).isEqualTo(0x89);
        assertThat(new String(artifact.content(), 1, 3)).isEqualTo("PNG");
    }

    @Test
    void videoProviderReturnsOneVideoLabelledArtifact() {
        MediaProviderOutcome outcome = provider.start(request(GenerationType.VIDEO));

        MediaArtifact artifact = ((MediaProviderOutcome.Completed) outcome).artifacts().get(0);
        assertThat(((MediaProviderOutcome.Completed) outcome).artifacts()).hasSize(1);
        assertThat(artifact.mimeType()).isEqualTo("video/mp4");
        assertThat(artifact.content().length).isGreaterThan(0);
    }

    private MediaProviderRequest request(GenerationType type) {
        return new MediaProviderRequest(
                UUID.randomUUID(),
                type,
                "stub-model",
                "coffee poster",
                "{}",
                ExecutionMode.INLINE_RESULT,
                List.of());
    }
}
