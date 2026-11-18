package app.pagefault.visionforge.agent.provider.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.volcengine.ark.runtime.models.images.CreateImageGenerationRequest;
import com.volcengine.ark.runtime.models.images.OutputFormat;
import com.volcengine.ark.runtime.models.images.ResponseFormat;

import app.pagefault.visionforge.agent.generation.GenerationInputRole;
import app.pagefault.visionforge.agent.generation.GenerationType;
import app.pagefault.visionforge.agent.model.ExecutionMode;

import tools.jackson.databind.ObjectMapper;

class ArkImagesProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ArkImagesProvider provider =
            new ArkImagesProvider(objectMapper, new ArkClientFactory());

    @Test
    void exposesConfiguredUserAgentAsCustomHeader() {
        assertThat(new ArkClientFactory("VisionForge-Test/1.0").customHeaders())
                .containsEntry("User-Agent", "VisionForge-Test/1.0");
        assertThat(new ArkClientFactory().customHeaders()).isEmpty();
    }

    @Test
    void mapsSeedreamRequestWithReferenceImages() {
        MediaProviderRequest request = new MediaProviderRequest(
                UUID.randomUUID(),
                GenerationType.IMAGE,
                "https://ark.example.com/api/v3",
                "test-key",
                "doubao-seedream-4-0",
                "a cat on a windowsill",
                """
                {"size":"2048x2048","outputFormat":"jpeg","watermark":false,"seed":7}
                """,
                ExecutionMode.INLINE_RESULT,
                List.of(
                        new MediaProviderInput(
                                UUID.randomUUID(),
                                GenerationInputRole.REFERENCE,
                                "image/png",
                                "https://media.example.com/ref-1.png",
                                null),
                        new MediaProviderInput(
                                UUID.randomUUID(),
                                GenerationInputRole.INIT_IMAGE,
                                "image/png",
                                "https://media.example.com/ref-2.png",
                                null)));

        CreateImageGenerationRequest built = provider.createRequest(request);

        assertThat(built.getModel()).isEqualTo("doubao-seedream-4-0");
        assertThat(built.getPrompt()).isEqualTo("a cat on a windowsill");
        assertThat(built.getSize()).isEqualTo("2048x2048");
        assertThat(built.getOutputFormat()).isEqualTo(OutputFormat.JPEG);
        assertThat(built.getResponseFormat()).isEqualTo(ResponseFormat.B64_JSON);
        assertThat(built.getWatermark()).isFalse();
        assertThat(built.getSeed()).isEqualTo(7L);
        assertThat(built.getImage().getValue())
                .isEqualTo(List.of(
                        "https://media.example.com/ref-1.png",
                        "https://media.example.com/ref-2.png"));
    }
}
