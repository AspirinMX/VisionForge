package app.pagefault.visionforge.agent.tool;

public record ToolResult(
        boolean success,
        String content,
        String errorCode,
        String errorMessage) {

    public static ToolResult success(String content) {
        return new ToolResult(true, content, null, null);
    }

    public static ToolResult failure(String errorCode, String errorMessage) {
        return new ToolResult(false, null, errorCode, errorMessage);
    }

    public String modelContent() {
        if (success) {
            return content == null ? "{}" : content;
        }
        return "{\"error\":{\"code\":\"" + escape(errorCode)
                + "\",\"message\":\"" + escape(errorMessage) + "\"}}";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
