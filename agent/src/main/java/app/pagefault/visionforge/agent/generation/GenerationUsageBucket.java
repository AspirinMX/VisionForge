package app.pagefault.visionforge.agent.generation;

import lombok.Getter;
import lombok.Setter;

/**
 * One {@code priceUnits} bucket of the recent-task usage summary, grouped by billing status.
 */
@Getter
@Setter
public class GenerationUsageBucket {

    private GenerationBillingStatus billingStatus;

    private long taskCount;

    private long priceUnits;
}
