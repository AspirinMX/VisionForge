package app.pagefault.visionforge.api.security;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import lombok.extern.slf4j.Slf4j;

/**
 * Atomic Redis sorted-set sliding-window rate limiter with bounded in-memory fallback.
 */
@Slf4j
public class SlidingWindowRateLimiter {

    private static final String KEY_PREFIX = "visionforge:ratelimit:";
    private static final int MAX_LOCAL_BUCKETS = 10_000;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SLIDING_WINDOW_SCRIPT = createScript();

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final RateLimitProperties properties;
    private final ConcurrentMap<String, Deque<Long>> localBuckets = new ConcurrentHashMap<>();

    public SlidingWindowRateLimiter(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            Clock clock,
            RateLimitProperties properties) {
        this(redisTemplateProvider.getIfAvailable(), clock, properties);
    }

    SlidingWindowRateLimiter(
            StringRedisTemplate redisTemplate,
            Clock clock,
            RateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.properties = properties;
    }

    public Decision tryAcquire(String bucket, String subject, int limit) {
        int windowSeconds = Math.max(1, properties.getWindowSeconds());
        if (!properties.isEnabled() || limit <= 0) {
            return new Decision(true, Math.max(0, limit), Math.max(0, limit), 0L);
        }
        String sanitizedSubject = (subject == null || subject.isBlank()) ? "anonymous" : subject;
        String key = KEY_PREFIX + bucket + ":" + sanitizedSubject;
        long nowMillis = clock.millis();
        long windowMillis = windowSeconds * 1000L;

        if (redisTemplate != null) {
            try {
                return acquireViaRedis(key, nowMillis, windowMillis, limit);
            } catch (RuntimeException exception) {
                if (properties.isFailOpen()) {
                    log.warn("Redis rate limiter failed open for bucket={}: {}", bucket, exception.getMessage());
                    return new Decision(true, limit, Math.max(0, limit - 1), 0L);
                }
                log.warn("Redis rate limiter failed closed for bucket={}: {}", bucket, exception.getMessage());
                return new Decision(false, limit, 0, Math.max(1L, windowSeconds));
            }
        }
        return acquireLocally(key, nowMillis, windowMillis, limit);
    }

    private Decision acquireViaRedis(String key, long nowMillis, long windowMillis, int limit) {
        String member = nowMillis + ":" + UUID.randomUUID();
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) redisTemplate.execute(
                SLIDING_WINDOW_SCRIPT,
                List.of(key),
                Long.toString(nowMillis),
                Long.toString(windowMillis),
                Integer.toString(limit),
                member);
        if (result == null || result.size() < 3) {
            throw new IllegalStateException("Unexpected empty rate limit script response");
        }
        boolean allowed = toLong(result.get(0)) == 1L;
        int remaining = (int) Math.max(0L, toLong(result.get(1)));
        long retryAfterMs = Math.max(0L, toLong(result.get(2)));
        long retryAfterSeconds = allowed ? 0L : Math.max(1L, (retryAfterMs + 999L) / 1000L);
        return new Decision(allowed, limit, remaining, retryAfterSeconds);
    }

    private Decision acquireLocally(String key, long nowMillis, long windowMillis, int limit) {
        if (localBuckets.size() >= MAX_LOCAL_BUCKETS && !localBuckets.containsKey(key)) {
            evictExpiredLocalBuckets(nowMillis - windowMillis);
        }
        Deque<Long> timestamps = localBuckets.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (timestamps) {
            long cutoff = nowMillis - windowMillis;
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= limit) {
                long oldest = timestamps.peekFirst() == null ? nowMillis : timestamps.peekFirst();
                long retryAfterMs = Math.max(1000L, windowMillis - (nowMillis - oldest));
                long retryAfterSeconds = Math.max(1L, (retryAfterMs + 999L) / 1000L);
                return new Decision(false, limit, 0, retryAfterSeconds);
            }
            timestamps.addLast(nowMillis);
            int remaining = Math.max(0, limit - timestamps.size());
            return new Decision(true, limit, remaining, 0L);
        }
    }

    private void evictExpiredLocalBuckets(long cutoff) {
        Iterator<ConcurrentMap.Entry<String, Deque<Long>>> iterator = localBuckets.entrySet().iterator();
        while (iterator.hasNext() && localBuckets.size() >= MAX_LOCAL_BUCKETS) {
            Deque<Long> entries = iterator.next().getValue();
            synchronized (entries) {
                while (!entries.isEmpty() && entries.peekFirst() <= cutoff) {
                    entries.pollFirst();
                }
                if (entries.isEmpty()) {
                    iterator.remove();
                }
            }
        }
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> createScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setResultType(List.class);
        script.setScriptText("""
                local key = KEYS[1]
                local now = tonumber(ARGV[1])
                local windowMs = tonumber(ARGV[2])
                local limit = tonumber(ARGV[3])
                local member = ARGV[4]
                redis.call('ZREMRANGEBYSCORE', key, '-inf', now - windowMs)
                local current = redis.call('ZCARD', key)
                if current >= limit then
                    local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
                    local retryAfterMs = windowMs
                    if oldest[2] ~= nil then
                        retryAfterMs = math.max(1000, windowMs - (now - tonumber(oldest[2])))
                    end
                    return {0, 0, retryAfterMs}
                end
                redis.call('ZADD', key, now, member)
                redis.call('PEXPIRE', key, windowMs + 1000)
                return {1, limit - current - 1, 0}
                """);
        return script;
    }

    public record Decision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {
    }
}
