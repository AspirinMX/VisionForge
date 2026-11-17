package app.pagefault.visionforge.common.error;

import java.util.Objects;

/**
 * An unexpected failure that indicates a defect or an unavailable dependency.
 *
 * <p>The original message and cause are kept for logs, but clients only receive the generic
 * message of the associated {@link ErrorCode}.
 */
public class SystemException extends RuntimeException {

    private final ErrorCode errorCode;

    public SystemException(String message) {
        this(CommonErrorCode.INTERNAL_ERROR, message, null);
    }

    public SystemException(String message, Throwable cause) {
        this(CommonErrorCode.INTERNAL_ERROR, message, cause);
    }

    public SystemException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
