package app.pagefault.visionforge.asset.storage;

/**
 * HTTP byte range. {@code end} is inclusive and may be null to read to the end of the object.
 */
public record ByteRange(long start, Long end) {

    public ByteRange {
        if (start < 0 || (end != null && end < start)) {
            throw new IllegalArgumentException("invalid byte range");
        }
    }

    public static ByteRange from(long start) {
        return new ByteRange(start, null);
    }

    public static ByteRange of(long start, long end) {
        return new ByteRange(start, end);
    }
}
