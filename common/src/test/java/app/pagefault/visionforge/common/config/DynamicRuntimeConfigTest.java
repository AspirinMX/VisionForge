package app.pagefault.visionforge.common.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.error.CommonErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicRuntimeConfigTest {

    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    @Test
    void seedsDefaultsAndHotReloadsValidJsonFromTheDatabaseStore() {
        InMemoryConfigStore store = new InMemoryConfigStore();
        DynamicRuntimeConfig config = new DynamicRuntimeConfig(
                store, 0, Clock.fixed(NOW, ZoneOffset.UTC));

        config.init();

        assertThat(config.initialCreditUnits(10_000L)).isEqualTo(10_000L);
        assertThat(config.rateLimitAuthPerMinute(20)).isEqualTo(20);
        assertThat(config.registrationEnabled(true)).isTrue();
        assertThat(config.listEntries()).hasSize(4);

        DynamicRuntimeConfig.ConfigEntry updated = config.publish(
                null,
                DynamicRuntimeConfig.DATA_ID_SECURITY,
                "{\"registrationEnabled\":false,\"rateLimitAuthPerMinute\":5}");

        assertThat(updated.source()).isEqualTo("DATABASE");
        assertThat(updated.version()).isEqualTo(2);
        assertThat(config.registrationEnabled(true)).isFalse();
        assertThat(config.rateLimitAuthPerMinute(20)).isEqualTo(5);

        assertThatThrownBy(() -> config.publish(
                null, DynamicRuntimeConfig.DATA_ID_SECURITY, "[1,2,3]"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_ARGUMENT));

        config.reset(null, DynamicRuntimeConfig.DATA_ID_SECURITY);
        assertThat(config.registrationEnabled(true)).isTrue();
        config.close();
    }

    @Test
    void refreshPicksUpChangesWrittenByAnotherInstance() {
        InMemoryConfigStore store = new InMemoryConfigStore();
        DynamicRuntimeConfig config = new DynamicRuntimeConfig(
                store, 0, Clock.fixed(NOW, ZoneOffset.UTC));
        config.init();

        store.save(
                DynamicRuntimeConfig.DATA_ID_BILLING,
                "{\"initialCreditUnits\":50000}",
                null);

        config.listEntries();

        assertThat(config.initialCreditUnits(10_000L)).isEqualTo(50_000L);
        config.close();
    }

    private static final class InMemoryConfigStore implements ConfigStore {

        private final Map<String, StoredConfig> rows = new ConcurrentHashMap<>();

        @Override
        public void ensure(String dataId, String content) {
            rows.putIfAbsent(dataId, new StoredConfig(content, 1, NOW));
        }

        @Override
        public Map<String, StoredConfig> loadAll() {
            return Map.copyOf(rows);
        }

        @Override
        public Map<String, Long> versions() {
            Map<String, Long> versions = new LinkedHashMap<>();
            rows.forEach((dataId, row) -> versions.put(dataId, row.version()));
            return versions;
        }

        @Override
        public StoredConfig save(String dataId, String content, UUID updatedBy) {
            StoredConfig current = rows.get(dataId);
            long nextVersion = current == null ? 1 : current.version() + 1;
            StoredConfig saved = new StoredConfig(content, nextVersion, NOW);
            rows.put(dataId, saved);
            return saved;
        }
    }
}
