package app.pagefault.visionforge.identity.user;

/**
 * System-level RBAC role. It only gates operational endpoints; it never grants access to another
 * user's conversations or files.
 */
public enum UserRole {
    ADMIN,
    USER
}
