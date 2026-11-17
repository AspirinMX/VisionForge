package app.pagefault.visionforge.asset.media;

import java.util.Locale;
import java.util.Set;

public enum AssetKind {
    IMAGE,
    VIDEO,
    DOCUMENT,
    OTHER;

    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif", "image/avif");
    private static final Set<String> VIDEO_TYPES = Set.of(
            "video/mp4", "video/quicktime", "video/webm");
    private static final Set<String> DOCUMENT_TYPES = Set.of(
            "application/pdf",
            "text/plain",
            "text/markdown",
            "application/json",
            "application/zip");

    public boolean supports(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return false;
        }
        String normalized = normalizeMimeType(mimeType);
        return switch (this) {
            case IMAGE -> IMAGE_TYPES.contains(normalized);
            case VIDEO -> VIDEO_TYPES.contains(normalized);
            case DOCUMENT -> DOCUMENT_TYPES.contains(normalized);
            case OTHER -> false;
        };
    }

    public static String normalizeMimeType(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        int parameter = mimeType.indexOf(';');
        String value = parameter >= 0 ? mimeType.substring(0, parameter) : mimeType;
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
