package app.pagefault.visionforge.agent.tool;

public record ToolResult(
        boolean success,
        String content,
        String errorCode,
        String errorMessage,
        boolean waiting,
        String waitTaskId) {

    public static ToolResult success(String content) {
        return new ToolResult(true, content, null, null, false, null);
    }

    public static ToolResult failure(String errorCode, String errorMessage) {
        return new ToolResult(false, null, errorCode, errorMessage, false, null);
    }

    public static ToolResult waiting(String taskId) {
        return new ToolResult(
                true,
                "{\"taskId\":\"" + taskId + "\",\"status\":\"WAITING\"}",
                null,
                null,
                true,
                taskId);
    }

    public String modelContent() {
        if (waiting) {
            return content == null ? "{}" : content;
        }
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
