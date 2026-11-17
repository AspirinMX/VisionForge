package app.pagefault.visionforge.common.error;

import java.util.Objects;

/**
 * An expected, user-facing failure caused by request data or business state.
 *
 * <p>Business exceptions are reported to clients with their message and are not logged with
 * a stack trace by default. Unexpected failures must use {@link SystemException} instead.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, Objects.requireNonNull(errorCode, "errorCode").message());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
