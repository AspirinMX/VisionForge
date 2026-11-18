package app.pagefault.visionforge.agent.provider.media;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.images.Image;
import com.openai.models.images.ImageEditParams;
import com.openai.models.images.ImageGenerateParams;
import com.openai.models.images.ImagesResponse;

import app.pagefault.visionforge.agent.generation.GenerationType;
import app.pagefault.visionforge.agent.model.MediaProtocol;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.agent.error.AgentErrorCode;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI Images protocol adapter for GPT Image generation. Reference-image editing is rejected
 * until the gateway-specific edit transport has been verified.
 */
@RequiredArgsConstructor
public class OpenAiImagesProvider implements MediaProvider {

    private static final int MAX_CACHED_CLIENTS = 32;
    private static final long MAX_RESULT_BYTES = 64L * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final HttpMediaDownloader downloader = new HttpMediaDownloader(MAX_RESULT_BYTES);
    private final Map<String, OpenAIClient> clients = new ConcurrentHashMap<>();

    @Override
    public MediaProtocol protocol() {
        return MediaProtocol.OPENAI_IMAGES;
    }

    @Override
    public MediaProviderOutcome start(MediaProviderRequest request) {
        if (request.type() != GenerationType.IMAGE) {
            throw unsupported("OpenAI Images 只支持图片生成");
        }
        try {
            JsonNode parameters = objectMapper.readTree(
                    request.parameters() == null ? "{}" : request.parameters());
            String size = text(parameters, "size");
            String quality = text(parameters, "quality");
            String outputFormat = text(parameters, "outputFormat");
            if (outputFormat == null) {
                outputFormat = text(parameters, "output_format");
            }

            ImagesResponse response;
            if (request.inputs().isEmpty()) {
                ImageGenerateParams.Builder params = ImageGenerateParams.builder()
                        .model(request.modelId())
                        .prompt(request.prompt())
                        .n(1L);
                if (size != null) {
                    params.size(size);
                }
                if (quality != null) {
                    params.quality(ImageGenerateParams.Quality.of(quality));
                }
                if (outputFormat != null) {
                    params.outputFormat(ImageGenerateParams.OutputFormat.of(outputFormat));
                }
                response = client(request).images().generate(params.build());
            } else {
                for (MediaProviderInput input : request.inputs()) {
                    if (input.role() == app.pagefault.visionforge.agent.generation.GenerationInputRole.MASK) {
                        throw unsupported("OpenAI Images 局部重绘 mask 尚未接入");
                    }
                    if (input.content() == null || input.content().length == 0) {
                        throw unsupported("OpenAI Images 参考图缺少可读取内容");
                    }
                }
                ImageEditParams.Builder params = ImageEditParams.builder()
                        .model(request.modelId())
                        .prompt(request.prompt())
                        .n(1L)
                        .imageOfInputStreams(request.inputs().stream()
                                .map(input -> new ByteArrayInputStream(input.content()))
                                .toList());
                if (size != null) {
                    params.size(size);
                }
                if (quality != null) {
                    params.quality(ImageEditParams.Quality.of(quality));
                }
                if (outputFormat != null) {
                    params.outputFormat(ImageEditParams.OutputFormat.of(outputFormat));
                }
                response = client(request).images().edit(params.build());
            }

            List<Image> data = response.data().orElse(List.of());
            if (data.isEmpty()) {
                throw failure("OpenAI Images 没有返回产物");
            }
            Image image = data.get(0);
            byte[] bytes;
            if (image.b64Json().isPresent()) {
                bytes = Base64.getDecoder().decode(image.b64Json().get());
            } else if (image.url().isPresent()) {
                bytes = downloader.download(image.url().get());
            } else {
                throw failure("OpenAI Images 返回的产物既没有 b64 也没有 URL");
            }
            String mime = mimeType(outputFormat);
            return new MediaProviderOutcome.Completed(
                    new MediaProviderReceipt(
                            request.taskId().toString(),
                            null,
                            "succeeded",
                            "{}"),
                    List.of(new MediaArtifact(0, mime, bytes)));
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure("OpenAI Images 调用失败：" + message(exception));
        }
    }

    private OpenAIClient client(MediaProviderRequest request) {
        String key = request.baseUrl() + '|' + request.apiKey();
        OpenAIClient cached = clients.get(key);
        if (cached != null) {
            return cached;
        }
        OpenAIClient created = OpenAIOkHttpClient.builder()
                .baseUrl(request.baseUrl())
                .apiKey(request.apiKey())
                .maxRetries(0)
                .timeout(Duration.ofMinutes(15))
                .build();
        if (clients.size() >= MAX_CACHED_CLIENTS) {
            clients.clear();
        }
        OpenAIClient existing = clients.putIfAbsent(key, created);
        return existing == null ? created : existing;
    }

    private String text(JsonNode parameters, String field) {
        JsonNode value = parameters.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private String mimeType(String outputFormat) {
        if (outputFormat == null) {
            return "image/png";
        }
        return switch (outputFormat.toLowerCase(java.util.Locale.ROOT)) {
            case "jpeg", "jpg" -> "image/jpeg";
            case "webp" -> "image/webp";
            default -> "image/png";
        };
    }

    private BusinessException unsupported(String message) {
        return new BusinessException(
                AgentErrorCode.GENERATION_CAPABILITY_UNSUPPORTED, message);
    }

    private BusinessException failure(String message) {
        return new BusinessException(
                AgentErrorCode.GENERATION_PROVIDER_ERROR, message);
    }

    private String message(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
