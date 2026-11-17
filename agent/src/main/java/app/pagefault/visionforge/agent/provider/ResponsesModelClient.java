package app.pagefault.visionforge.agent.provider;

/**
 * Streams one model response. Implementations own the upstream transport; callers only see
 * increments and the accumulated result.
 */
public interface ResponsesModelClient {

    ModelResponse stream(ModelRequest request, ModelStreamListener listener);
}
