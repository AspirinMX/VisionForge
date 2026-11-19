package app.pagefault.visionforge.common.alert;

/**
 * Non-blocking SPI for publishing alerts to configured Email and Webhook channels.
 */
public interface AlertPublisher {

    AlertPublisher NOOP = event -> {
    };

    void publish(AlertEvent event);
}
