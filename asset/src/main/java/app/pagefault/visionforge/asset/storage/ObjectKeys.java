package app.pagefault.visionforge.asset.storage;

import java.util.Locale;
import java.util.UUID;

/**
 * Allocates server-owned object keys. Keys embed the owner and conversation so an operator can
 * trace an object without consulting the database, but authorization is always enforced in the
 * application layer.
 */
public final class ObjectKeys {

    private static final int MAX_FILENAME_LENGTH = 120;
    private static final String FALLBACK_NAME = "file";

    private ObjectKeys() {
    }

    public static String upload(
            UUID userId, UUID conversationId, UUID uploadSessionId, String filename) {
        return "users/%s/conversations/%s/uploads/%s/%s".formatted(
                userId, conversationId, uploadSessionId, sanitizeFilename(filename));
    }

    /**
     * Final, immutable location of an uploaded asset. The staging key that accepted the presigned
     * PUT is copied here and is never used as the permanent address.
     */
    public static String asset(
            UUID userId, UUID conversationId, UUID assetId, String extension) {
        return "users/%s/conversations/%s/assets/%s/original.%s".formatted(
                userId, conversationId, assetId, sanitizeExtension(extension));
    }

    public static String generation(
            UUID userId,
            UUID conversationId,
            UUID generationTaskId,
            UUID outputId,
            String extension) {
        return "users/%s/conversations/%s/generations/%s/%s.%s".formatted(
                userId, conversationId, generationTaskId, outputId, sanitizeExtension(extension));
    }

    public static String composition(
            UUID userId,
            UUID conversationId,
            UUID compositionTaskId,
            String extension) {
        return "users/%s/conversations/%s/compositions/%s/final.%s".formatted(
                userId, conversationId, compositionTaskId, sanitizeExtension(extension));
    }

    public static String variant(
            UUID userId,
            UUID conversationId,
            UUID assetId,
            String variant,
            String extension) {
        return "users/%s/conversations/%s/variants/%s/%s.%s".formatted(
                userId,
                conversationId,
                assetId,
                sanitizeFilename(variant),
                sanitizeExtension(extension));
    }

    /**
     * Keeps only a safe base name. Path separators, traversal segments and control characters are
     * replaced, so a caller cannot escape the allocated prefix.
     */
    public static String sanitizeFilename(String filename) {
        String value = filename == null ? "" : filename.replace('\\', '/');
        int separator = value.lastIndexOf('/');
        if (separator >= 0) {
            value = value.substring(separator + 1);
        }
        value = value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (value.isEmpty() || ".".equals(value) || "..".equals(value)) {
            return FALLBACK_NAME;
        }
        if (value.length() <= MAX_FILENAME_LENGTH) {
            return value;
        }
        int dot = value.lastIndexOf('.');
        if (dot <= 0 || dot == value.length() - 1) {
            return value.substring(0, MAX_FILENAME_LENGTH);
        }
        String extension = value.substring(dot);
        int baseLength = Math.max(1, MAX_FILENAME_LENGTH - extension.length());
        return value.substring(0, baseLength) + extension;
    }

    public static String sanitizeExtension(String extension) {
        String value = extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith(".")) {
            value = value.substring(1);
        }
        return value.matches("[a-z0-9]{1,10}") ? value : "bin";
    }
}
