package app.pagefault.visionforge.common.id;

import java.util.UUID;

/**
 * Generates business identifiers. Implementations must return time-ordered UUIDv7 values
 * unless a specific caller documents otherwise.
 */
@FunctionalInterface
public interface IdGenerator {

    UUID nextId();
}
