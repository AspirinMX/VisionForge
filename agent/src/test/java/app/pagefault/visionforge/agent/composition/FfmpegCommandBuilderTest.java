package app.pagefault.visionforge.agent.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.pagefault.visionforge.agent.composition.FfmpegCommandBuilder.CompositionClip;
import app.pagefault.visionforge.common.error.BusinessException;

import tools.jackson.databind.ObjectMapper;

class FfmpegCommandBuilderTest {

    private final FfmpegCommandBuilder builder = new FfmpegCommandBuilder();

    @Test
    void buildsSequentialConcatRecipe() {
        List<String> command = builder.build(
                "ffmpeg",
                List.of(
                        new CompositionClip("https://s3/a.mp4", BigDecimal.ZERO, BigDecimal.ONE),
                        new CompositionClip("https://s3/b.mp4", BigDecimal.ONE, BigDecimal.valueOf(3))),
                builder.defaults());

        assertThat(command).containsSubsequence("-filter_complex", "-map", "[vout]");
        assertThat(command).contains("-an", "-c:v", "libx264", "-f", "mp4", "pipe:1");
        assertThat(command.get(command.indexOf("-filter_complex") + 1))
                .contains("[0:v]", "[1:v]", "concat=n=2:v=1:a=0[vout]");
        assertThat(command).containsExactly(
                "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
                "-ss", "0", "-t", "1", "-i", "https://s3/a.mp4",
                "-ss", "1", "-t", "2", "-i", "https://s3/b.mp4",
                "-filter_complex", command.get(command.indexOf("-filter_complex") + 1),
                "-map", "[vout]", "-an", "-c:v", "libx264", "-preset", "veryfast",
                "-crf", "23", "-pix_fmt", "yuv420p", "-movflags", "frag_keyframe+empty_moov",
                "-f", "mp4", "pipe:1");
    }

    @Test
    void singleClipMapsItsFilterOutputDirectly() {
        List<String> command = builder.build(
                "ffmpeg",
                List.of(new CompositionClip(
                        "https://s3/a.mp4", BigDecimal.ZERO, BigDecimal.ONE)),
                builder.defaults());

        assertThat(command).contains("-map", "[v0]");
        assertThat(command.get(command.indexOf("-filter_complex") + 1))
                .doesNotContain("concat=");
    }

    @Test
    void rejectsUnsupportedPreset() {
        var spec = new ObjectMapper().readTree("{\"preset\":\"insane\"}");

        assertThatThrownBy(() -> builder.parseSpec(spec, builder.defaults()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsOutOfRangeSize() {
        var spec = new ObjectMapper().readTree("{\"width\":1}");

        assertThatThrownBy(() -> builder.parseSpec(spec, builder.defaults()))
                .isInstanceOf(BusinessException.class);
    }
}
