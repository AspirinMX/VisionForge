package app.pagefault.visionforge.identity.workspace;

public enum WorkspaceRole {

    OWNER(3),
    ADMIN(2),
    MEMBER(1);

    private final int rank;

    WorkspaceRole(int rank) {
        this.rank = rank;
    }

    public boolean atLeast(WorkspaceRole required) {
        return rank >= required.rank;
    }
}
