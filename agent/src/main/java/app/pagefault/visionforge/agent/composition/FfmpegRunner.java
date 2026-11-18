package app.pagefault.visionforge.agent.composition;

import java.util.List;

public interface FfmpegRunner {

    Process start(List<String> command);
}
