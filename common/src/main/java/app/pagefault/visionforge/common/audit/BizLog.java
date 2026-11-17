package app.pagefault.visionforge.common.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an auditable business operation.
 *
 * <p>{@code success} and {@code fail} are templates where {@code {{expression}}} fragments
 * are evaluated with SpEL. Available variables include method arguments, {@code #result},
 * {@code #errorMessage}, {@code #operator} and {@code #traceId}. Masking functions are
 * available as {@code #maskEmail(...)}, {@code #maskPhone(...)} and {@code #maskToken(...)}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BizLog {

    /** Module/object type, for example {@code IDENTITY_USER}. */
    String type();

    /** Action, for example {@code REGISTER} or {@code ROLE_CHANGE}. */
    String subType();

    /** SpEL expression returning the business object id. */
    String bizNo() default "";

    /** Success description template. */
    String success() default "";

    /** Failure description template. */
    String fail() default "";

    /** SpEL condition; logging is skipped when it evaluates to false. */
    String condition() default "";

    /** Whether to record a failed invocation. */
    boolean recordOnFailure() default true;

    /** Whether to store synchronously instead of using the audit executor. */
    boolean sync() default false;
}
