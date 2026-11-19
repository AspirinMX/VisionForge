package app.pagefault.visionforge.agent.provider.media;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.generation.GenerationInputRole;
import app.pagefault.visionforge.agent.generation.GenerationType;
import app.pagefault.visionforge.agent.model.MediaProtocol;
import app.pagefault.visionforge.common.error.BusinessException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Ark Seedream image adapter speaking the documented {@code /api/v3/images/generations} JSON
 * contract. Reference images are submitted as URLs in the {@code image} field.
 */
public class ArkImagesProvider implements MediaProvider {

    private static final String GENERATE_PATH = "/api/v3/images/generations";
    private static final long MAX_RESULT_BYTES = 64L * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final ArkClientFactory clients;
    private final HttpMediaDownloader downloader;

    public ArkImagesProvider(ObjectMapper objectMapper, ArkClientFactory clients) {
        this(objectMapper, clients, "");
    }

    public ArkImagesProvider(
            ObjectMapper objectMapper, ArkClientFactory clients, String userAgent) {
        this(objectMapper, clients, new HttpMediaDownloader(MAX_RESULT_BYTES, userAgent));
    }

    ArkImagesProvider(
            ObjectMapper objectMapper,
            ArkClientFactory clients,
            HttpMediaDownloader downloader) {
        this.objectMapper = objectMapper;
        this.clients = clients;
        this.downloader = downloader;
    }

    @Override
    public MediaProtocol protocol() {
        return MediaProtocol.ARK_IMAGES;
    }

    @Override
    public MediaProviderOutcome start(MediaProviderRequest request) {
        if (request.type() != GenerationType.IMAGE) {
            throw unsupported("Ark 图片适配器只接受图片任务");
        }
        try {
            ObjectNode payload = createRequest(request);
            JsonNode response = clients.post(request, GENERATE_PATH, payload, objectMapper);
            JsonNode topError = response.path("error");
            if (!topError.isMissingNode() && !topError.isNull()) {
                throw failure(errorMessage(topError));
            }
            JsonNode data = response.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw failure("Ark 图片接口没有返回产物");
            }
            JsonNode item = data.get(0);
            JsonNode itemError = item.path("error");
            if (!itemError.isMissingNode() && !itemError.isNull()) {
                throw failure(errorMessage(itemError));
            }
            String b64 = item.path("b64_json").asText("");
            String url = item.path("url").asText("");
            byte[] bytes;
            if (!b64.isBlank()) {
                bytes = Base64.getDecoder().decode(b64);
            } else if (!url.isBlank()) {
                bytes = downloader.download(url);
            } else {
                throw failure("Ark 图片产物既没有 b64_json 也没有 URL");
            }
            String requestedFormat = payload.path("output_format").asText("");
            String returnedFormat = item.path("output_format").asText("");
            String mimeType = mimeType(requestedFormat, returnedFormat);
            return new MediaProviderOutcome.Completed(
                    receipt(request, response), List.of(new MediaArtifact(0, mimeType, bytes)));
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure("Ark 图片调用失败：" + message(exception));
        }
    }

    ObjectNode createRequest(MediaProviderRequest request) {
        JsonNode parameters = readParameters(request);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.modelId());
        root.put("prompt", request.prompt());
        root.put("response_format", "b64_json");

        String size = text(parameters, "size");
        if (size != null) {
            root.put("size", size);
        }
        String outputFormat = text(parameters, "outputFormat", "output_format");
        if (outputFormat != null) {
            root.put("output_format", validateOutputFormat(outputFormat));
        }
        Boolean watermark = booleanValue(parameters, "watermark");
        if (watermark != null) {
            root.put("watermark", watermark);
        }
        Long seed = longValue(parameters, "seed");
        if (seed != null) {
            root.put("seed", seed);
        }
        Double guidanceScale = doubleValue(parameters, "guidanceScale", "guidance_scale");
        if (guidanceScale != null) {
            root.put("guidance_scale", guidanceScale);
        }
        Double imageGuidanceScale =
                doubleValue(parameters, "imageGuidanceScale", "image_guidance_scale");
        if (imageGuidanceScale != null) {
            root.put("image_guidance_scale", imageGuidanceScale);
        }

        if (!request.inputs().isEmpty()) {
            ArrayNode imageArray = root.putArray("image");
            for (MediaProviderInput input : request.inputs()) {
                if (input.role() == GenerationInputRole.MASK) {
                    throw unsupported("Ark 图片暂不支持 mask 输入");
                }
                String payload = MediaProviderInputs.inlineOrUrl(input);
                if (payload == null || payload.isBlank()) {
                    throw unsupported("Ark 图片参考素材缺少可访问 URL");
                }
                imageArray.add(payload);
            }
        }
        return root;
    }

    private MediaProviderReceipt receipt(MediaProviderRequest request, JsonNode response) {
        Map<String, Object> usageMap = new LinkedHashMap<>();
        JsonNode usage = response.path("usage");
        if (!usage.isMissingNode() && !usage.isNull() && usage.isObject()) {
            if (!usage.path("input_images").isMissingNode() && !usage.path("input_images").isNull()) {
                usageMap.put("inputImages", usage.path("input_images").asLong());
            }
            if (!usage.path("generated_images").isMissingNode() && !usage.path("generated_images").isNull()) {
                usageMap.put("generatedImages", usage.path("generated_images").asLong());
            }
            if (!usage.path("output_tokens").isMissingNode() && !usage.path("output_tokens").isNull()) {
                usageMap.put("outputTokens", usage.path("output_tokens").asLong());
            }
            if (!usage.path("total_tokens").isMissingNode() && !usage.path("total_tokens").isNull()) {
                usageMap.put("totalTokens", usage.path("total_tokens").asLong());
            }
        }
        return new MediaProviderReceipt(
                request.taskId().toString(), null, "succeeded", write(usageMap));
    }

    private String validateOutputFormat(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!"png".equals(normalized) && !"jpeg".equals(normalized)) {
            throw unsupported("Ark 图片不支持的输出格式：" + value);
        }
        return normalized;
    }

    private String mimeType(String requested, String returned) {
        String effective = (returned == null || returned.isBlank()) ? requested : returned;
        if ("jpeg".equalsIgnoreCase(effective) || "jpg".equalsIgnoreCase(effective)) {
            return "image/jpeg";
        }
        return "image/png";
    }

    private String errorMessage(JsonNode error) {
        String code = error.path("code").asText("error");
        String message = error.path("message").asText("上游返回错误");
        return "Ark 图片生成失败：" + code + ": " + message;
    }

    private JsonNode readParameters(MediaProviderRequest request) {
        try {
            return objectMapper.readTree(
                    request.parameters() == null ? "{}" : request.parameters());
        } catch (RuntimeException exception) {
            throw unsupported("Ark 图片参数不是合法 JSON");
        }
    }

    private String text(JsonNode parameters, String... fields) {
        for (String field : fields) {
            JsonNode value = parameters.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    private Boolean booleanValue(JsonNode parameters, String... fields) {
        for (String field : fields) {
            JsonNode value = parameters.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asBoolean();
            }
        }
        return null;
    }

    private Long longValue(JsonNode parameters, String... fields) {
        for (String field : fields) {
            JsonNode value = parameters.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asLong();
            }
        }
        return null;
    }

    private Double doubleValue(JsonNode parameters, String... fields) {
        for (String field : fields) {
            JsonNode value = parameters.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asDouble();
            }
        }
        return null;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            return "{}";
        }
    }

    private BusinessException unsupported(String message) {
        return new BusinessException(AgentErrorCode.GENERATION_CAPABILITY_UNSUPPORTED, message);
    }

    private BusinessException failure(String message) {
        return new BusinessException(AgentErrorCode.GENERATION_PROVIDER_ERROR, message);
    }

    private String message(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
