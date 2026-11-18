package app.pagefault.visionforge.agent.composition;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;

import tools.jackson.databind.JsonNode;

/**
 * Builds a fixed FFmpeg recipe for sequential shot composition. User/model input never becomes a
 * shell command or a raw filtergraph: only validated numeric fields and server-generated input
 * URLs are placed into the argument list.
 */
public class FfmpegCommandBuilder {

    private static final Set<String> PRESETS =
            Set.of("ultrafast", "superfast", "veryfast", "faster", "fast", "medium");

    public CompositionSpec parseSpec(JsonNode node, CompositionSpec defaults) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaults;
        }
        if (!node.isObject()) {
            throw invalid("成片输出规格必须是 JSON 对象");
        }
        int width = intValue(node, "width", defaults.width(), 16, 4096);
        int height = intValue(node, "height", defaults.height(), 16, 4096);
        int fps = intValue(node, "fps", defaults.fps(), 1, 60);
        int crf = intValue(node, "crf", defaults.crf(), 0, 51);
        String codec = textValue(node, "videoCodec", defaults.videoCodec());
        if (!"h264".equals(codec)) {
            throw invalid("首版只支持 h264 成片编码");
        }
        String preset = textValue(node, "preset", defaults.preset());
        if (!PRESETS.contains(preset)) {
            throw invalid("不支持的 FFmpeg preset：" + preset);
        }
        return new CompositionSpec(width, height, fps, codec, preset, crf);
    }

    public List<String> build(
            String ffmpegPath, List<CompositionClip> clips, CompositionSpec spec) {
        if (clips == null || clips.isEmpty()) {
            throw invalid("成片至少需要一个镜头片段");
        }
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-nostdin");
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        for (CompositionClip clip : clips) {
            command.add("-ss");
            command.add(clip.startSeconds().toPlainString());
            command.add("-t");
            command.add(clip.endSeconds().subtract(clip.startSeconds()).toPlainString());
            command.add("-i");
            command.add(clip.url());
        }

        StringBuilder filter = new StringBuilder();
        for (int index = 0; index < clips.size(); index++) {
            if (index > 0) {
                filter.append(';');
            }
            filter.append('[').append(index).append(":v]")
                    .append("scale=").append(spec.width()).append(':').append(spec.height())
                    .append(":force_original_aspect_ratio=decrease,")
                    .append("pad=").append(spec.width()).append(':').append(spec.height())
                    .append(":(ow-iw)/2:(oh-ih)/2,setsar=1,fps=").append(spec.fps())
                    .append(",format=yuv420p,setpts=PTS-STARTPTS[v").append(index).append(']');
        }
        String outputLabel;
        if (clips.size() == 1) {
            outputLabel = "[v0]";
        } else {
            filter.append(';');
            for (int index = 0; index < clips.size(); index++) {
                filter.append("[v").append(index).append(']');
            }
            filter.append("concat=n=").append(clips.size()).append(":v=1:a=0[vout]");
            outputLabel = "[vout]";
        }
        command.add("-filter_complex");
        command.add(filter.toString());
        command.add("-map");
        command.add(outputLabel);
        command.add("-an");
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add(spec.preset());
        command.add("-crf");
        command.add(Integer.toString(spec.crf()));
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-movflags");
        command.add("frag_keyframe+empty_moov");
        command.add("-f");
        command.add("mp4");
        command.add("pipe:1");
        return command;
    }

    public CompositionSpec defaults() {
        return new CompositionSpec(1280, 720, 30, "h264", "veryfast", 23);
    }

    private int intValue(JsonNode node, String field, int defaultValue, int min, int max) {
        JsonNode value = node.path(field);
        int result = value.isMissingNode() || value.isNull() ? defaultValue : value.asInt();
        if (result < min || result > max) {
            throw invalid(field + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return result;
    }

    private String textValue(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        String result = value.asText();
        if (result == null || result.isBlank()) {
            return defaultValue;
        }
        return result;
    }

    private BusinessException invalid(String message) {
        return new BusinessException(AgentErrorCode.GENERATION_INPUT_INVALID, message);
    }

    public record CompositionClip(
            String url, java.math.BigDecimal startSeconds, java.math.BigDecimal endSeconds) {
    }
}
