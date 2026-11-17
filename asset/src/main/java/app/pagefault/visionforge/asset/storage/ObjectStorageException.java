package app.pagefault.visionforge.asset.storage;

/**
 * Backend-neutral storage failure. {@link #isNotFound()} lets callers distinguish a missing object
 * from an unavailable backend without importing vendor exceptions.
 */
public class ObjectStorageException extends RuntimeException {

    private final boolean notFound;

    public ObjectStorageException(boolean notFound, String message, Throwable cause) {
        super(message, cause);
        this.notFound = notFound;
    }

    public boolean isNotFound() {
        return notFound;
    }
}
