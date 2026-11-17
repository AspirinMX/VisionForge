package app.pagefault.visionforge.common.audit;

@FunctionalInterface
public interface OperatorProvider {

    Operator current();
}
