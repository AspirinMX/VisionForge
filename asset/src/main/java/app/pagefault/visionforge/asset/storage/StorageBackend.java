package app.pagefault.visionforge.asset.storage;

/**
 * Storage backend recorded on each asset. New backends implement {@link ObjectStorage} and add a
 * value here; existing assets keep the backend they were written with until migrated.
 */
public enum StorageBackend {
    S3,
    LOCAL
}
