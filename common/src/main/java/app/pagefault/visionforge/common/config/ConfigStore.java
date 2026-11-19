package app.pagefault.visionforge.common.config;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence boundary for the four managed JSON configuration documents.
 *
 * <p>The application uses PostgreSQL so configuration is backed up and audited together with
 * business data. Implementations must be safe to call from API and worker instances.</p>
 */
public interface ConfigStore {

    void ensure(String dataId, String content);

    Map<String, StoredConfig> loadAll();

    Map<String, Long> versions();

    StoredConfig save(String dataId, String content, UUID updatedBy);

    record StoredConfig(String content, long version, Instant updatedAt) {
    }
}
