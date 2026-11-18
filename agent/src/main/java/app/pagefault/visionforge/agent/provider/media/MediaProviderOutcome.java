package app.pagefault.visionforge.agent.provider.media;

import java.util.List;

public sealed interface MediaProviderOutcome {

    record Completed(MediaProviderReceipt receipt, List<MediaArtifact> artifacts)
            implements MediaProviderOutcome {

        public Completed {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }
    }

    record Pending(MediaProviderReceipt receipt, String remoteTaskId)
            implements MediaProviderOutcome {
    }

    record Failed(String providerStatus, String message) implements MediaProviderOutcome {
    }

    record Cancelled(String message) implements MediaProviderOutcome {
    }
}
