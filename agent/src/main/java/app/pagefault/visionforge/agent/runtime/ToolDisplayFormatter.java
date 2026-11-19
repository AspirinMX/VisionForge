package app.pagefault.visionforge.agent.runtime;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * Maps internal tool names and raw JSON arguments/results into concise, user-facing action labels
 * and safe targets without exposing raw payloads or internal identifiers.
 */
public final class ToolDisplayFormatter {

    private static final int MAX_TARGET_LENGTH = 48;
    private static final Pattern TRAILING_DIGITS = Pattern.compile("(\\d+)$");

    private final ObjectMapper objectMapper;

    public ToolDisplayFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Display format(String toolName, String argumentsJson, String resultJson) {
        JsonNode args = parseObject(argumentsJson);
        JsonNode res = parseObject(resultJson);
        String name = toolName == null ? "" : toolName;

        return switch (name) {
            case "load_skill" -> new Display(
                    "读取创作指南",
                    cleanTarget(text(args, "name")),
                    null,
                    null);
            case "list_files" -> new Display(
                    "查看工作区文件",
                    kindLabel(text(args, "kind")),
                    null,
                    null);
            case "search_files" -> new Display(
                    "搜索工作区文件",
                    cleanTarget(firstNonBlank(text(args, "query"), kindLabel(text(args, "kind")))),
                    null,
                    null);
            case "read_file" -> new Display(
                    "读取文件",
                    cleanTarget(firstNonBlank(text(res, "path"), text(res, "title"))),
                    firstUuid(uuid(res, "fileId"), uuid(args, "fileId")),
                    null);
            case "create_file" -> {
                JsonNode file = res.path("file");
                yield new Display(
                        "创建文件",
                        cleanTarget(firstNonBlank(
                                text(file, "path"),
                                text(file, "title"),
                                text(args, "path"),
                                text(args, "title"))),
                        uuid(file, "fileId"),
                        null);
            }
            case "update_file" -> {
                JsonNode file = res.path("file");
                yield new Display(
                        "更新文件",
                        cleanTarget(firstNonBlank(
                                text(file, "path"),
                                text(file, "title"),
                                text(args, "title"))),
                        firstUuid(uuid(file, "fileId"), uuid(args, "fileId")),
                        null);
            }
            case "list_file_versions" -> new Display(
                    "查看版本记录",
                    null,
                    uuid(args, "fileId"),
                    null);
            case "save_storyboard" -> new Display(
                    "保存分镜",
                    cleanTarget(firstNonBlank(
                            text(res, "title"),
                            text(args, "path"),
                            text(args.path("storyboard"), "title"))),
                    firstUuid(uuid(res, "fileId"), uuid(args, "fileId")),
                    null);
            case "validate_storyboard" -> new Display(
                    "校验分镜结构",
                    res.path("shotCount").isInt()
                            ? res.path("shotCount").asInt() + " 个镜头"
                            : null,
                    firstUuid(uuid(res, "fileId"), uuid(args, "fileId")),
                    null);
            case "generate_image" -> new Display(
                    "生成图片",
                    shotOrPrompt(text(args, "shotId"), text(args, "prompt")),
                    uuid(args, "storyboardFileId"),
                    uuid(res, "taskId"));
            case "generate_video" -> new Display(
                    "生成视频",
                    shotOrPrompt(text(args, "shotId"), text(args, "prompt")),
                    uuid(args, "storyboardFileId"),
                    uuid(res, "taskId"));
            case "await_generation" -> new Display(
                    "等待生成完成",
                    shotOrType(text(res, "shotId"), text(res, "type")),
                    null,
                    firstUuid(uuid(res, "taskId"), uuid(args, "taskId")));
            case "get_generation" -> new Display(
                    "查询生成进度",
                    shotOrType(text(res, "shotId"), text(res, "type")),
                    null,
                    firstUuid(uuid(res, "taskId"), uuid(args, "taskId")));
            case "cancel_generation" -> new Display(
                    "取消生成任务",
                    shotOrType(text(res, "shotId"), text(res, "type")),
                    null,
                    firstUuid(uuid(res, "taskId"), uuid(args, "taskId")));
            case "inspect_asset" -> new Display(
                    "读取素材信息",
                    assetTarget(res),
                    null,
                    null);
            case "compose_video" -> new Display(
                    "合成成片",
                    versionTarget(res, args),
                    uuid(args, "storyboardFileId"),
                    uuid(res, "taskId"));
            case "get_composition" -> new Display(
                    "查询成片进度",
                    versionTarget(res, args),
                    null,
                    firstUuid(uuid(res, "taskId"), uuid(args, "taskId")));
            default -> new Display("执行工具", null, null, null);
        };
    }

    private JsonNode parseObject(String json) {
        if (json == null || json.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node == null ? MissingNode.getInstance() : node;
        } catch (RuntimeException ignored) {
            return MissingNode.getInstance();
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (!value.isString()) {
            return null;
        }
        String raw = value.asText();
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private UUID uuid(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private UUID firstUuid(UUID primary, UUID secondary) {
        return primary != null ? primary : secondary;
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private String kindLabel(String kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "SCRIPT" -> "剧本";
            case "STORYBOARD" -> "分镜";
            case "PROMPT" -> "提示词";
            case "IMAGE" -> "图片";
            case "VIDEO" -> "视频";
            case "OTHER" -> "其他文件";
            default -> "文件";
        };
    }

    private String shotLabel(String shotId) {
        Matcher matcher = TRAILING_DIGITS.matcher(shotId);
        if (!matcher.find()) {
            return "镜头";
        }
        try {
            return "第 " + Integer.parseInt(matcher.group(1)) + " 镜";
        } catch (NumberFormatException ignored) {
            return "镜头";
        }
    }

    private String shotOrPrompt(String shotId, String prompt) {
        if (shotId != null) {
            return shotLabel(shotId);
        }
        return cleanTarget(prompt);
    }

    private String shotOrType(String shotId, String type) {
        if (shotId != null) {
            return shotLabel(shotId);
        }
        if ("IMAGE".equals(type)) {
            return "图片任务";
        }
        if ("VIDEO".equals(type)) {
            return "视频任务";
        }
        return null;
    }

    private String assetTarget(JsonNode res) {
        JsonNode width = res.path("width");
        JsonNode height = res.path("height");
        if (width.isInt() && height.isInt() && width.asInt() > 0 && height.asInt() > 0) {
            return width.asInt() + "×" + height.asInt();
        }
        String kind = text(res, "kind");
        return kindLabel(kind);
    }

    private String versionTarget(JsonNode res, JsonNode args) {
        if (res.path("storyboardVersion").asInt(0) > 0
                || args.path("storyboardVersion").asInt(0) > 0) {
            return "分镜合成";
        }
        return null;
    }

    private String cleanTarget(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String singleLine = raw.replaceAll("\\s+", " ").trim();
        if (singleLine.length() <= MAX_TARGET_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, MAX_TARGET_LENGTH - 1) + "…";
    }

    public record Display(String action, String target, UUID fileId, UUID taskId) {
    }
}
