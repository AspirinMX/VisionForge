package app.pagefault.visionforge.agent.temporal.selfcheck;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface SelfCheckActivities {

    String greet(String input);
}
