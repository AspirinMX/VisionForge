package app.pagefault.visionforge.agent.tool;

import java.util.Locale;
import java.util.UUID;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;

import tools.jackson.databind.JsonNode;

public final class ToolArguments {

    private ToolArguments() {
    }

    public static String requiredText(JsonNode arguments, String field) {
        String value = optionalText(arguments, field);
        if (value == null || value.isBlank()) {
            throw invalid("缺少参数 " + field);
        }
        return value.trim();
    }

    public static String optionalText(JsonNode arguments, String field) {
        JsonNode value = arguments.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid("参数 " + field + " 必须是字符串");
        }
        return value.asText();
    }

    public static UUID requiredUuid(JsonNode arguments, String field) {
        String value = requiredText(arguments, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw invalid("参数 " + field + " 必须是 UUID");
        }
    }

    public static UUID optionalUuid(JsonNode arguments, String field) {
        String value = optionalText(arguments, field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw invalid("参数 " + field + " 必须是 UUID");
        }
    }

    public static Integer optionalInt(JsonNode arguments, String field) {
        JsonNode value = arguments.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        if (value.isTextual()) {
            try {
                return Integer.valueOf(value.asText().trim());
            } catch (NumberFormatException exception) {
                throw invalid("参数 " + field + " 必须是整数");
            }
        }
        throw invalid("参数 " + field + " 必须是整数");
    }

    public static boolean optionalBoolean(JsonNode arguments, String field, boolean defaultValue) {
        JsonNode value = arguments.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        throw invalid("参数 " + field + " 必须是布尔值");
    }

    public static <E extends Enum<E>> E optionalEnum(
            JsonNode arguments, String field, Class<E> type) {
        String value = optionalText(arguments, field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            throw invalid("参数 " + field + " 的值不合法");
        }
    }

    public static int limit(JsonNode arguments, int defaultValue, int maximum) {
        Integer value = optionalInt(arguments, "limit");
        if (value == null) {
            return defaultValue;
        }
        return Math.min(Math.max(value, 1), maximum);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(AgentErrorCode.TOOL_ARGUMENTS_INVALID, message);
    }
}
