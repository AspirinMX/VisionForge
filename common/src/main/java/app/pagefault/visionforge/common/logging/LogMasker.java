package app.pagefault.visionforge.common.logging;

/**
 * Helpers for keeping personally identifiable information and credentials out of logs.
 */
public final class LogMasker {

    private static final String MASK = "***";

    private LogMasker() {
    }

    public static String email(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return MASK;
        }
        return email.charAt(0) + MASK + email.substring(at);
    }

    public static String phone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "";
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() < 7) {
            return MASK;
        }
        return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
    }

    public static String secret(String secret) {
        return secret == null || secret.isBlank() ? "" : MASK;
    }

    public static String token(String token) {
        return secret(token);
    }
}
