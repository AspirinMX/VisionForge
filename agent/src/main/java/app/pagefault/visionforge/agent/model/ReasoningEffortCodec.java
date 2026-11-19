package app.pagefault.visionforge.agent.model;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;

/**
 * Canonical normalization and persistence codec for per-model {@link ReasoningEffort} capability
 * lists. Values are always deduplicated and ordered to match {@link ReasoningEffort#values()}.
 */
public final class ReasoningEffortCodec {

    private ReasoningEffortCodec() {
    }

    public static List<ReasoningEffort> normalize(Collection<ReasoningEffort> efforts) {
        if (efforts == null || efforts.isEmpty()) {
            return List.of();
        }
        Set<ReasoningEffort> unique = EnumSet.noneOf(ReasoningEffort.class);
        for (ReasoningEffort effort : efforts) {
            if (effort == null) {
                throw new BusinessException(
                        AgentErrorCode.MODEL_CONFIG_INVALID, "支持的推理档位不能包含空值");
            }
            unique.add(effort);
        }
        return List.copyOf(unique);
    }

    public static String encode(Collection<ReasoningEffort> efforts) {
        List<ReasoningEffort> normalized = normalize(efforts);
        if (normalized.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < normalized.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(normalized.get(i).name()).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    public static List<ReasoningEffort> decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String trimmed = raw.trim();
        if ("[]".equals(trimmed)) {
            return List.of();
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            return List.of();
        }
        Set<ReasoningEffort> unique = EnumSet.noneOf(ReasoningEffort.class);
        for (String token : trimmed.split(",")) {
            String cleaned = token.trim();
            if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
                cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
            }
            if (cleaned.isEmpty()) {
                continue;
            }
            try {
                unique.add(ReasoningEffort.valueOf(cleaned.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Legacy values outside the canonical enum are ignored on read.
            }
        }
        return List.copyOf(unique);
    }
}
