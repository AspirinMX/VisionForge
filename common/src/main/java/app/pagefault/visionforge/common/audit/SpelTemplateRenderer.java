package app.pagefault.visionforge.common.audit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import lombok.extern.slf4j.Slf4j;

/**
 * Renders {@code {{expression}}} templates and evaluates standalone SpEL expressions.
 * Parsed expressions are cached; authors of annotations are trusted code.
 *
 * <p>Evaluation failures degrade to an empty value so a broken optional expression does
 * not drop the whole audit record.
 */
@Slf4j
final class SpelTemplateRenderer {

    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{(.+?)}}");

    private final ExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

    String render(String template, StandardEvaluationContext context) {
        if (template == null || template.isBlank()) {
            return null;
        }
        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Object value = evaluateSafely(matcher.group(1).trim(), context);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? "" : value.toString()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    String evaluate(String expression, StandardEvaluationContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        Object value = evaluateSafely(expression.trim(), context);
        return value == null ? null : value.toString();
    }

    boolean evaluateBoolean(String expression, StandardEvaluationContext context, boolean defaultValue) {
        if (expression == null || expression.isBlank()) {
            return defaultValue;
        }
        Object value = evaluateSafely(expression.trim(), context);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private Object evaluateSafely(String expression, StandardEvaluationContext context) {
        try {
            Expression parsed = expressionCache.computeIfAbsent(expression, parser::parseExpression);
            return parsed.getValue(context);
        } catch (RuntimeException exception) {
            log.warn("Business log expression could not be evaluated: {}", expression);
            return null;
        }
    }
}
