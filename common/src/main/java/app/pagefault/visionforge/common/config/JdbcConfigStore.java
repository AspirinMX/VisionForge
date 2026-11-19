package app.pagefault.visionforge.common.config;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * PostgreSQL-backed configuration store. Content is stored as JSONB so it can be
 * queried and validated by the database as well as by the application.
 */
public class JdbcConfigStore implements ConfigStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcConfigStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void ensure(String dataId, String content) {
        jdbcTemplate.update("""
                INSERT INTO system_config (data_id, content, version, updated_at)
                VALUES (?, CAST(? AS jsonb), 1, now())
                ON CONFLICT (data_id) DO NOTHING
                """, dataId, content);
    }

    @Override
    public Map<String, StoredConfig> loadAll() {
        Map<String, StoredConfig> rows = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT data_id, content, version, updated_at
                  FROM system_config
                """,
                (RowCallbackHandler) rs -> rows.put(
                        rs.getString("data_id"),
                        new StoredConfig(
                                rs.getString("content"),
                                rs.getLong("version"),
                                toInstant(rs.getTimestamp("updated_at")))));
        return rows;
    }

    @Override
    public Map<String, Long> versions() {
        Map<String, Long> versions = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT data_id, version
                  FROM system_config
                """,
                (RowCallbackHandler) rs ->
                        versions.put(rs.getString("data_id"), rs.getLong("version")));
        return versions;
    }

    @Override
    public StoredConfig save(String dataId, String content, UUID updatedBy) {
        StoredConfig saved = jdbcTemplate.queryForObject("""
                INSERT INTO system_config (data_id, content, version, updated_by, updated_at)
                VALUES (?, CAST(? AS jsonb), 1, ?, now())
                ON CONFLICT (data_id) DO UPDATE
                   SET content = EXCLUDED.content,
                       version = system_config.version + 1,
                       updated_by = EXCLUDED.updated_by,
                       updated_at = now()
                RETURNING content, version, updated_at
                """,
                (rs, rowNum) -> new StoredConfig(
                        rs.getString("content"),
                        rs.getLong("version"),
                        toInstant(rs.getTimestamp("updated_at"))),
                dataId,
                content,
                updatedBy);
        if (saved == null) {
            throw new IllegalStateException("配置写入未返回结果: " + dataId);
        }
        return saved;
    }

    private static Instant toInstant(java.sql.Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }
}
