package app.pagefault.visionforge.agent.provider;

import java.util.Objects;

import tools.jackson.databind.JsonNode;

/**
 * Provider-neutral function tool definition. {@code parameters} is a JSON Schema object.
 */
public record ModelToolSpec(String name, String description, JsonNode parameters) {

    public ModelToolSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parameters, "parameters");
    }
}
