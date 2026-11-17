package app.pagefault.visionforge.common.id;

import java.time.Clock;
import java.util.UUID;

import cn.hutool.core.util.RandomUtil;

/**
 * Generates RFC 9562 UUIDv7 values.
 *
 * <p>Layout: 48-bit Unix epoch milliseconds, 4-bit version, 12-bit {@code rand_a},
 * 2-bit variant ({@code 10}) and 62-bit {@code rand_b}. Values are ordered by millisecond
 * but not strictly monotonic within the same millisecond.
 */
public final class UuidV7Generator implements IdGenerator {

    private static final long TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL;
    private static final long RAND_A_MASK = 0xFFFL;
    private static final long RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL;
    private static final long VARIANT_10 = 0x8000_0000_0000_0000L;

    private final Clock clock;

    public UuidV7Generator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public UUID nextId() {
        long timestamp = clock.millis() & TIMESTAMP_MASK;
        long randA = RandomUtil.getRandom().nextLong() & RAND_A_MASK;
        long mostSignificantBits = (timestamp << 16) | (0x7L << 12) | randA;

        long randB = RandomUtil.getRandom().nextLong() & RAND_B_MASK;
        long leastSignificantBits = VARIANT_10 | randB;

        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
