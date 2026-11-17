package app.pagefault.visionforge.asset.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ObjectKeysTest {

    private static final UUID USER_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e34");
    private static final UUID CONVERSATION_ID =
            UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e30");
    private static final UUID SESSION_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e35");

    @Test
    void stripsPathTraversalFromUploadedFileNames() {
        assertThat(ObjectKeys.sanitizeFilename("../../etc/passwd")).isEqualTo("passwd");
        assertThat(ObjectKeys.sanitizeFilename("..\\..\\evil.png")).isEqualTo("evil.png");
        assertThat(ObjectKeys.sanitizeFilename("/")).isEqualTo("file");
    }

    @Test
    void keepsTheExtensionWhenTruncatingLongNames() {
        String name = "a".repeat(200) + ".png";

        String sanitized = ObjectKeys.sanitizeFilename(name);

        assertThat(sanitized).hasSizeLessThanOrEqualTo(120);
        assertThat(sanitized).endsWith(".png");
    }

    @Test
    void allocatesUploadKeysUnderTheOwnerAndConversation() {
        String key = ObjectKeys.upload(USER_ID, CONVERSATION_ID, SESSION_ID, "photo.png");

        assertThat(key).isEqualTo(
                "users/" + USER_ID + "/conversations/" + CONVERSATION_ID
                        + "/uploads/" + SESSION_ID + "/photo.png");
    }

    @Test
    void normalizesGeneratedExtensions() {
        assertThat(ObjectKeys.sanitizeExtension(".PNG")).isEqualTo("png");
        assertThat(ObjectKeys.sanitizeExtension("../png")).isEqualTo("bin");
        assertThat(ObjectKeys.sanitizeExtension(null)).isEqualTo("bin");
    }
}
