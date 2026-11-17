package app.pagefault.visionforge.common.audit;

/**
 * Persists audit records. Implementations must be thread-safe and must not throw for
 * transient storage failures; the aspect isolates failures regardless.
 */
@FunctionalInterface
public interface BizLogStore {

    void save(BizLogRecord record);
}
