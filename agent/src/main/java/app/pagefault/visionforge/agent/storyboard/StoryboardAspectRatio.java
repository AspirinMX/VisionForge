package app.pagefault.visionforge.agent.storyboard;

import java.util.regex.Pattern;

public record StoryboardAspectRatio(int horizontal, int vertical) {

    private static final Pattern RATIO = Pattern.compile("([1-9][0-9]{0,3}):([1-9][0-9]{0,3})");

    public static StoryboardAspectRatio parse(String value) {
        var match = RATIO.matcher(value == null ? "" : value);
        return match.matches()
                ? new StoryboardAspectRatio(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)))
                : null;
    }

    public String text() {
        return horizontal + ":" + vertical;
    }

    public Dimensions fit(int longestSide, int multiple) {
        int longestRatioSide = Math.max(horizontal, vertical);
        return new Dimensions(scale(horizontal, longestRatioSide, longestSide, multiple),
                scale(vertical, longestRatioSide, longestSide, multiple));
    }

    private static int scale(int side, int longestRatioSide, int longestSide, int multiple) {
        return Math.max(16, (int) Math.round((double) side * longestSide / longestRatioSide / multiple) * multiple);
    }

    public record Dimensions(int width, int height) {
        public String text(String separator) {
            return width + separator + height;
        }
    }
}
