package app.pagefault.visionforge.common.logging;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/**
 * Request correlation support built on SLF4J MDC.
 *
 * <p>The trace id is written to logs through the {@code traceId} MDC key and returned to
 * clients in the {@code X-Request-Id} response header. Incoming ids are accepted only when
 * they are short and unambiguous; otherwise a new id is generated.
 */
public final class TraceContext {

    public static final String TRACE_ID_MDC_KEY = "traceId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final Pattern VALID_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private TraceContext() {
    }

    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static boolean isValidTraceId(String candidate) {
        return candidate != null && VALID_TRACE_ID.matcher(candidate).matches();
    }

    /**
     * Stores {@code candidate} when it is valid, otherwise stores a newly generated id.
     *
     * @return the trace id that was stored
     */
    public static String ensureTraceId(String candidate) {
        String traceId = isValidTraceId(candidate) ? candidate : generateTraceId();
        setTraceId(traceId);
        return traceId;
    }

    public static void setTraceId(String traceId) {
        MDC.put(TRACE_ID_MDC_KEY, traceId);
    }

    public static String currentTraceId() {
        return MDC.get(TRACE_ID_MDC_KEY);
    }

    public static void clear() {
        MDC.remove(TRACE_ID_MDC_KEY);
    }
}
