package app.pagefault.visionforge.agent.provider.media;

import app.pagefault.visionforge.agent.model.MediaProtocol;

/**
 * Protocol adapter boundary. Implementations translate a domain request to one upstream call and
 * never retry a paid submit; retries and waiting belong to the workflow.
 */
public interface MediaProvider {

    MediaProtocol protocol();

    MediaProviderOutcome start(MediaProviderRequest request);
}
