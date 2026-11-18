package app.pagefault.visionforge.agent.provider.media;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.volcengine.ark.runtime.models.images.CreateImageGenerationRequest;
import com.volcengine.ark.runtime.models.images.CreateImageGenerationRequestImage;
import com.volcengine.ark.runtime.models.images.ImageDataItem;
import com.volcengine.ark.runtime.models.images.ImageError;
import com.volcengine.ark.runtime.models.images.ImageGenerationResponse;
import com.volcengine.ark.runtime.models.images.OutputFormat;
import com.volcengine.ark.runtime.models.images.ResponseFormat;
import com.volcengine.ark.runtime.service.ArkService;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.generation.GenerationInputRole;
import app.pagefault.visionforge.agent.generation.GenerationType;
import app.pagefault.visionforge.agent.model.MediaProtocol;
import app.pagefault.visionforge.common.error.BusinessException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Ark Seedream image adapter. Reference images are submitted as URLs in the {@code image} field,
 * which is the native Ark Images contract rather than the OpenAI edits endpoint.
 */
public class ArkImagesProvider implements MediaProvider {

    private static final long MAX_RESULT_BYTES = 64L * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final ArkClientFactory clients;
    private final HttpMediaDownloader downloader;

    public ArkImagesProvider(ObjectMapper objectMapper, ArkClientFactory clients) {
        this(objectMapper, clients, "");
    }

    public ArkImagesProvider(
            ObjectMapper objectMapper, ArkClientFactory clients, String userAgent) {
        this.objectMapper = objectMapper;
        this.clients = clients;
        this.downloader = new HttpMediaDownloader(MAX_RESULT_BYTES, userAgent);
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
            CreateImageGenerationRequest imageRequest = createRequest(request);
            ArkService service = clients.get(request);
            ImageGenerationResponse response =
                    service.generateImages(imageRequest, clients.customHeaders());
            if (response == null) {
                throw failure("Ark 图片接口没有返回响应");
            }
            if (response.getError() != null) {
                throw failure(errorMessage(response.getError()));
            }
            List<ImageDataItem> data = response.getData();
            if (data == null || data.isEmpty()) {
                throw failure("Ark 图片接口没有返回产物");
            }
            ImageDataItem item = data.get(0);
            if (item.getError() != null) {
                throw failure(errorMessage(item.getError()));
            }
            byte[] bytes;
            if (item.getB64Json() != null && !item.getB64Json().isBlank()) {
                bytes = Base64.getDecoder().decode(item.getB64Json());
            } else if (item.getUrl() != null && !item.getUrl().isBlank()) {
                bytes = downloader.download(item.getUrl());
            } else {
                throw failure("Ark 图片产物既没有 b64_json 也没有 URL");
            }
            String mimeType = mimeType(imageRequest.getOutputFormat(), item.getOutputFormat());
            return new MediaProviderOutcome.Completed(
                    receipt(request, response), List.of(new MediaArtifact(0, mimeType, bytes)));
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure("Ark 图片调用失败：" + message(exception));
        }
    }

    CreateImageGenerationRequest createRequest(MediaProviderRequest request) {
        JsonNode parameters = readParameters(request);
        CreateImageGenerationRequest.Builder builder = CreateImageGenerationRequest.builder()
                .model(request.modelId())
                .prompt(request.prompt())
                .responseFormat(ResponseFormat.B64_JSON);

        String size = text(parameters, "size");
        if (size != null) {
            builder.size(size);
        }
        String outputFormat = text(parameters, "outputFormat", "output_format");
        if (outputFormat != null) {
            builder.outputFormat(outputFormat(outputFormat));
        }
        Boolean watermark = booleanValue(parameters, "watermark");
        if (watermark != null) {
            builder.watermark(watermark);
        }
        Long seed = longValue(parameters, "seed");
        if (seed != null) {
            builder.seed(seed);
        }
        Double guidanceScale = doubleValue(parameters, "guidanceScale", "guidance_scale");
        if (guidanceScale != null) {
            builder.guidanceScale(guidanceScale);
        }
        Double imageGuidanceScale =
                doubleValue(parameters, "imageGuidanceScale", "image_guidance_scale");
        if (imageGuidanceScale != null) {
            builder.imageGuidanceScale(imageGuidanceScale);
        }

        List<String> imageUrls = new ArrayList<>();
        for (MediaProviderInput input : request.inputs()) {
            if (input.role() == GenerationInputRole.MASK) {
                throw unsupported("Ark 图片暂不支持 mask 输入");
            }
            if (input.url() == null || input.url().isBlank()) {
                throw unsupported("Ark 图片参考素材缺少可访问 URL");
            }
            imageUrls.add(input.url());
        }
        if (!imageUrls.isEmpty()) {
            builder.image(CreateImageGenerationRequestImage.ofList(imageUrls));
        }
        return builder.build();
    }

    private MediaProviderReceipt receipt(
            MediaProviderRequest request, ImageGenerationResponse response) {
        Map<String, Object> usage = new LinkedHashMap<>();
        if (response.getUsage() != null) {
            usage.put("inputImages", response.getUsage().getInputImages());
            usage.put("generatedImages", response.getUsage().getGeneratedImages());
            usage.put("outputTokens", response.getUsage().getOutputTokens());
            usage.put("totalTokens", response.getUsage().getTotalTokens());
        }
        return new MediaProviderReceipt(
                request.taskId().toString(), null, "succeeded", write(usage));
    }

    private OutputFormat outputFormat(String value) {
        try {
            return OutputFormat.fromValue(value);
        } catch (IllegalArgumentException exception) {
            throw unsupported("Ark 图片不支持的输出格式：" + value);
        }
    }

    private String mimeType(OutputFormat requested, OutputFormat returned) {
        OutputFormat format = returned == null ? requested : returned;
        if (format == OutputFormat.JPEG) {
            return "image/jpeg";
        }
        return "image/png";
    }

    private String errorMessage(ImageError error) {
        String code = error.getCode() == null ? "error" : error.getCode();
        String message = error.getMessage() == null ? "上游返回错误" : error.getMessage();
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
