package app.pagefault.visionforge.agent.composition;

public record CompositionSpec(
        int width,
        int height,
        int fps,
        String videoCodec,
        String preset,
        int crf) {
}
