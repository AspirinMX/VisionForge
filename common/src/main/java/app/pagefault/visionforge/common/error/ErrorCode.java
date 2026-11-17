package app.pagefault.visionforge.common.error;

/**
 * Contract for all business and technical error codes.
 *
 * <p>Codes are stable, namespaced identifiers (for example {@code COMMON_INVALID_ARGUMENT})
 * that clients can switch on. Messages are intended for humans and may change.
 */
public interface ErrorCode {

    String code();

    int httpStatus();

    String message();
}
