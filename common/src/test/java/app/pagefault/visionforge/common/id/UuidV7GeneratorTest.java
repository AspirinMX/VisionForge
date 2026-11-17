package app.pagefault.visionforge.common.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {

    @Test
    void generatesVersion7AndIetfVariant() {
        UuidV7Generator generator = new UuidV7Generator(Clock.systemUTC());

        UUID id = generator.nextId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void encodesClockTimestamp() {
        Instant instant = Instant.parse("2026-11-17T12:00:00Z");
        UuidV7Generator generator = new UuidV7Generator(Clock.fixed(instant, ZoneOffset.UTC));

        UUID id = generator.nextId();

        long encodedTimestamp = id.getMostSignificantBits() >>> 16;
        assertThat(encodedTimestamp).isEqualTo(instant.toEpochMilli());
    }

    @Test
    void generatesUniqueIds() {
        UuidV7Generator generator = new UuidV7Generator(Clock.systemUTC());
        Set<UUID> ids = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            ids.add(generator.nextId());
        }

        assertThat(ids).hasSize(10_000);
    }

    @Test
    void ordersAcrossMilliseconds() {
        UuidV7Generator early = new UuidV7Generator(
                Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC));
        UuidV7Generator late = new UuidV7Generator(
                Clock.fixed(Instant.parse("2026-11-17T12:00:01Z"), ZoneOffset.UTC));

        UUID earlyId = early.nextId();
        UUID lateId = late.nextId();

        assertThat(earlyId.getMostSignificantBits()).isLessThan(lateId.getMostSignificantBits());
    }
}
