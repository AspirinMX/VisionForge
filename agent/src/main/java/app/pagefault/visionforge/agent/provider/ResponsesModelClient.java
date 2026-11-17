package app.pagefault.visionforge.agent.provider;

import java.util.function.Consumer;

/**
 * Streams one model response. Implementations own the upstream transport; callers only see text
 * deltas and the accumulated result.
 */
public interface ResponsesModelClient {

    ModelResponse stream(ModelRequest request, Consumer<String> onTextDelta);
}
