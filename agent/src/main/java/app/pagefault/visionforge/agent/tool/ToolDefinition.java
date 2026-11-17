package app.pagefault.visionforge.agent.tool;

import java.util.Objects;

import tools.jackson.databind.JsonNode;

public record ToolDefinition(
        String name,
        String description,
        JsonNode inputSchema,
        boolean sideEffect,
        int timeoutSeconds) {

    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Tool description must not be blank");
        }
        Objects.requireNonNull(inputSchema, "inputSchema");
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("Tool timeout must be positive");
        }
    }
}
