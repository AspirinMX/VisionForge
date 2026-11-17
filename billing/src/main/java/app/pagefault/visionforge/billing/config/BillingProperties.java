package app.pagefault.visionforge.billing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Initial grant for a user's credit account. Until an admin grant API exists this is the only way
 * a fresh account becomes usable.
 */
@ConfigurationProperties(prefix = "visionforge.billing")
public record BillingProperties(long initialCreditUnits) {
}
