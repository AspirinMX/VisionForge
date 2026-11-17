package app.pagefault.visionforge.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogMaskerTest {

    @Test
    void masksEmailLocalPartAndKeepsDomain() {
        assertThat(LogMasker.email("alice@example.com")).isEqualTo("a***@example.com");
    }

    @Test
    void masksMalformedEmailCompletely() {
        assertThat(LogMasker.email("not-an-email")).isEqualTo("***");
        assertThat(LogMasker.email("@example.com")).isEqualTo("***");
    }

    @Test
    void returnsEmptyForBlankEmail() {
        assertThat(LogMasker.email(null)).isEmpty();
        assertThat(LogMasker.email("  ")).isEmpty();
    }

    @Test
    void masksPhoneNumber() {
        assertThat(LogMasker.phone("13800138000")).isEqualTo("138****8000");
    }

    @Test
    void masksShortPhoneCompletely() {
        assertThat(LogMasker.phone("12345")).isEqualTo("***");
    }

    @Test
    void masksSecretsAndTokens() {
        assertThat(LogMasker.secret("super-secret")).isEqualTo("***");
        assertThat(LogMasker.token("eyJhbGciOiJIUzI1NiJ9")).isEqualTo("***");
        assertThat(LogMasker.token(null)).isEmpty();
    }
}
