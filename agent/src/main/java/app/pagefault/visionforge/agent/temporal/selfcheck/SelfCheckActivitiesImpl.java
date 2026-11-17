package app.pagefault.visionforge.agent.temporal.selfcheck;

public class SelfCheckActivitiesImpl implements SelfCheckActivities {

    @Override
    public String greet(String input) {
        return "pong:" + input;
    }
}
