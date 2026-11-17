package app.pagefault.visionforge.common.audit;

import java.lang.reflect.Method;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import app.pagefault.visionforge.common.id.IdGenerator;
import app.pagefault.visionforge.common.logging.LogMasker;
import app.pagefault.visionforge.common.logging.TraceContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Records auditable operations declared with {@link BizLog}.
 *
 * <p>Audit failures never affect the business result. Records are built on the caller
 * thread (so operator, trace id and SpEL variables are available) and stored
 * asynchronously by default.
 */
@Slf4j
@Aspect
@RequiredArgsConstructor
public class BizLogAspect {

    private static final DefaultParameterNameDiscoverer PARAMETER_NAME_DISCOVERER =
            new DefaultParameterNameDiscoverer();

    private final BizLogStore store;
    private final OperatorProvider operatorProvider;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final Executor executor;
    private final SpelTemplateRenderer renderer = new SpelTemplateRenderer();

    @Around("@annotation(bizLog)")
    public Object around(ProceedingJoinPoint joinPoint, BizLog bizLog) throws Throwable {
        Object result = null;
        Throwable failure = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable throwable) {
            failure = throwable;
            throw throwable;
        } finally {
            try {
                record(joinPoint, bizLog, result, failure);
            } catch (RuntimeException exception) {
                log.error("Failed to create business log [type={}, subType={}]",
                        bizLog.type(), bizLog.subType(), exception);
            }
        }
    }

    private void record(ProceedingJoinPoint joinPoint, BizLog bizLog, Object result, Throwable failure) {
        if (failure != null && !bizLog.recordOnFailure()) {
            return;
        }
        Operator operator = resolveOperator();
        String traceId = TraceContext.currentTraceId();
        StandardEvaluationContext context = createContext(joinPoint, result, failure, operator, traceId);

        if (!renderer.evaluateBoolean(bizLog.condition(), context, true)) {
            return;
        }

        String bizNo = renderer.evaluate(bizLog.bizNo(), context);
        String detail = failure == null
                ? renderer.render(bizLog.success(), context)
                : renderer.render(bizLog.fail(), context);
        String errorMessage = failure == null ? null : messageOf(failure);

        BizLogRecord record = new BizLogRecord(
                idGenerator.nextId(),
                bizLog.type(),
                bizLog.subType(),
                bizNo,
                operator.id(),
                operator.name(),
                failure == null,
                detail,
                null,
                traceId,
                errorMessage,
                clock.instant());

        if (bizLog.sync()) {
            safeSave(record);
        } else {
            submit(record);
        }
    }

    private StandardEvaluationContext createContext(
            ProceedingJoinPoint joinPoint, Object result, Throwable failure, Operator operator, String traceId) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object target = joinPoint.getTarget();
        Method targetMethod = target == null
                ? method
                : AopUtils.getMostSpecificMethod(method, target.getClass());

        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                target, targetMethod, joinPoint.getArgs(), PARAMETER_NAME_DISCOVERER);
        context.setVariable("result", result);
        context.setVariable("errorMessage", failure == null ? null : messageOf(failure));
        context.setVariable("operator", operator);
        context.setVariable("traceId", traceId);
        registerMaskFunctions(context);
        return context;
    }

    private void registerMaskFunctions(StandardEvaluationContext context) {
        try {
            context.registerFunction("maskEmail", LogMasker.class.getDeclaredMethod("email", String.class));
            context.registerFunction("maskPhone", LogMasker.class.getDeclaredMethod("phone", String.class));
            context.registerFunction("maskToken", LogMasker.class.getDeclaredMethod("token", String.class));
            context.registerFunction("maskSecret", LogMasker.class.getDeclaredMethod("secret", String.class));
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Log mask functions are missing", exception);
        }
    }

    private Operator resolveOperator() {
        try {
            Operator operator = operatorProvider.current();
            return operator == null ? Operator.system() : operator;
        } catch (RuntimeException exception) {
            log.warn("Failed to resolve operator for business log", exception);
            return Operator.system();
        }
    }

    private void submit(BizLogRecord record) {
        try {
            executor.execute(() -> safeSave(record));
        } catch (RuntimeException exception) {
            log.error("Failed to submit business log [type={}, subType={}, bizNo={}]",
                    record.type(), record.subType(), record.bizNo(), exception);
        }
    }

    private void safeSave(BizLogRecord record) {
        try {
            store.save(record);
        } catch (RuntimeException exception) {
            log.error("Failed to persist business log [type={}, subType={}, bizNo={}]",
                    record.type(), record.subType(), record.bizNo(), exception);
        }
    }

    private String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
