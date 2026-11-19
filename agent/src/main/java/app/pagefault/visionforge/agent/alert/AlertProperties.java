package app.pagefault.visionforge.agent.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "visionforge.alert")
public class AlertProperties {

    private boolean enabled = true;

    private boolean webhookAllowPrivateNetwork = false;

    private Mail mail = new Mail();

    @Getter
    @Setter
    public static class Mail {
        private String host = "";
        private int port = 587;
        private String username = "";
        private String password = "";
        private String from = "";
        private boolean sslEnabled = true;
    }
}
