package app.pagefault.visionforge.common.alert;

public enum AlertSeverity {
    INFO(0),
    WARNING(1),
    CRITICAL(2);

    private final int level;

    AlertSeverity(int level) {
        this.level = level;
    }

    public boolean satisfies(AlertSeverity threshold) {
        return threshold == null || this.level >= threshold.level;
    }
}
