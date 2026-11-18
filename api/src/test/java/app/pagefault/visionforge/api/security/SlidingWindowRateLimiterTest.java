package app.pagefault.visionforge.api.security;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

class SlidingWindowRateLimiterTest {

    @Test
    void localFallbackEnforcesSlidingWindowAndResetsAfterWindowExpires() {
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        Clock clock = new MutableClock(now);
        RateLimitProperties props = new RateLimitProperties();
        props.setWindowSeconds(60);

        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter((StringRedisTemplate) null, clock, props);

        SlidingWindowRateLimiter.Decision first = limiter.tryAcquire("auth", "ip:1.2.3.4", 2);
        SlidingWindowRateLimiter.Decision second = limiter.tryAcquire("auth", "ip:1.2.3.4", 2);
        SlidingWindowRateLimiter.Decision third = limiter.tryAcquire("auth", "ip:1.2.3.4", 2);

        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(1);
        assertThat(second.allowed()).isTrue();
        assertThat(second.remaining()).isZero();
        assertThat(third.allowed()).isFalse();
        assertThat(third.remaining()).isZero();
        assertThat(third.retryAfterSeconds()).isBetween(59L, 60L);

        now.addAndGet(61_000L);
        SlidingWindowRateLimiter.Decision afterWindow = limiter.tryAcquire("auth", "ip:1.2.3.4", 2);
        assertThat(afterWindow.allowed()).isTrue();
        assertThat(afterWindow.remaining()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisDecisionMapsAllowedAndRetryAfterSeconds() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        RateLimitProperties props = new RateLimitProperties();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(List.of(1L, 4L, 0L))
                .thenReturn(List.of(0L, 0L, 2_500L));

        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(redisTemplate, clock, props);

        SlidingWindowRateLimiter.Decision allowed = limiter.tryAcquire("auth", "ip:10.0.0.1", 5);
        SlidingWindowRateLimiter.Decision denied = limiter.tryAcquire("auth", "ip:10.0.0.1", 5);

        assertThat(allowed).isEqualTo(new SlidingWindowRateLimiter.Decision(true, 5, 4, 0L));
        assertThat(denied).isEqualTo(new SlidingWindowRateLimiter.Decision(false, 5, 0, 3L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisFailureRespectsFailOpenAndFailClosedConfiguration() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

        RateLimitProperties failOpenProps = new RateLimitProperties();
        failOpenProps.setFailOpen(true);
        SlidingWindowRateLimiter failOpenLimiter = new SlidingWindowRateLimiter(redisTemplate, clock, failOpenProps);
        assertThat(failOpenLimiter.tryAcquire("auth", "ip:1.1.1.1", 5).allowed()).isTrue();

        RateLimitProperties failClosedProps = new RateLimitProperties();
        failClosedProps.setFailOpen(false);
        failClosedProps.setWindowSeconds(45);
        SlidingWindowRateLimiter failClosedLimiter = new SlidingWindowRateLimiter(redisTemplate, clock, failClosedProps);
        SlidingWindowRateLimiter.Decision blocked = failClosedLimiter.tryAcquire("auth", "ip:1.1.1.1", 5);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isEqualTo(45L);
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong millis;

        private MutableClock(AtomicLong millis) {
            this.millis = millis;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public long millis() {
            return millis.get();
        }
    }
}
