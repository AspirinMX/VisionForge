package app.pagefault.visionforge.agent.composition;

import java.io.IOException;
import java.util.List;

import app.pagefault.visionforge.common.error.SystemException;

public class ProcessBuilderFfmpegRunner implements FfmpegRunner {

    @Override
    public Process start(List<String> command) {
        try {
            return new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new SystemException("无法启动 FFmpeg", exception);
        }
    }
}
