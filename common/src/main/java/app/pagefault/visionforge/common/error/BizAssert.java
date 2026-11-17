package app.pagefault.visionforge.common.error;

import java.util.Collection;

/**
 * Assertions that raise {@link BusinessException} with a meaningful error code.
 */
public final class BizAssert {

    private BizAssert() {
    }

    public static <T> T notNull(T value, ErrorCode errorCode) {
        if (value == null) {
            throw new BusinessException(errorCode);
        }
        return value;
    }

    public static String notBlank(String value, ErrorCode errorCode) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(errorCode);
        }
        return value;
    }

    public static <T extends Collection<?>> T notEmpty(T value, ErrorCode errorCode) {
        if (value == null || value.isEmpty()) {
            throw new BusinessException(errorCode);
        }
        return value;
    }

    public static void isTrue(boolean expression, ErrorCode errorCode) {
        if (!expression) {
            throw new BusinessException(errorCode);
        }
    }

    public static void isTrue(boolean expression, ErrorCode errorCode, String message) {
        if (!expression) {
            throw new BusinessException(errorCode, message);
        }
    }
}
