package app.pagefault.visionforge.common.audit;

import java.util.UUID;

/**
 * The authenticated principal performing an audited operation.
 */
public record Operator(UUID id, String name) {

    public static Operator system() {
        return new Operator(null, "system");
    }
}
