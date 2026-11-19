package app.pagefault.visionforge.common.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory config fallback used when Nacos integration is disabled.
 */
public class LocalConfigGateway implements ConfigCenterGateway {

    private final Map<String, String> configs = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Consumer<String>>> listeners = new ConcurrentHashMap<>();

    @Override
    public String sourceName() {
        return "LOCAL_FALLBACK";
    }

    @Override
    public boolean isRemoteActive() {
        return false;
    }

    @Override
    public String getConfig(String dataId) {
        return configs.get(dataId);
    }

    @Override
    public boolean publishConfig(String dataId, String content, String type) {
        configs.put(dataId, content);
        CopyOnWriteArrayList<Consumer<String>> callbacks = listeners.get(dataId);
        if (callbacks != null) {
            for (Consumer<String> callback : callbacks) {
                callback.accept(content);
            }
        }
        return true;
    }

    @Override
    public void addListener(String dataId, Consumer<String> onUpdate) {
        listeners.computeIfAbsent(dataId, ignored -> new CopyOnWriteArrayList<>()).add(onUpdate);
    }

    @Override
    public void close() {
        listeners.clear();
    }
}
