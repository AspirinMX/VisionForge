package app.pagefault.visionforge.agent.provider.media;

/**
 * Optional capability for providers that return a remote task id instead of a finished artifact.
 * Polling and cancellation semantics stay in the workflow; adapters only translate one query.
 */
public interface RemoteMediaProvider extends MediaProvider {

    MediaProviderOutcome query(MediaProviderRequest request, String remoteTaskId);
}
