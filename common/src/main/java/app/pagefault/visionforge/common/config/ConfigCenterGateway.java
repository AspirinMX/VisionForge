package app.pagefault.visionforge.common.config;

import java.util.function.Consumer;

/**
 * Abstraction over Nacos ConfigService with local fallback when Nacos is disabled.
 */
public interface ConfigCenterGateway {

    String sourceName();

    boolean isRemoteActive();

    String getConfig(String dataId);

    boolean publishConfig(String dataId, String content, String type);

    void addListener(String dataId, Consumer<String> onUpdate);

    void close();
}
