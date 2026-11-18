package app.pagefault.visionforge.agent.storyboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;

import tools.jackson.databind.ObjectMapper;

class StoryboardValidatorTest {

    private final StoryboardValidator validator = new StoryboardValidator(new ObjectMapper());

    @Test
    void acceptsValidStoryboard() {
        StoryboardSummary summary = validator.validate(storyboard("""
                "firstFramePrompt": "爪尖悬在水面上方",
                "videoPrompt": "爪尖落下，水面泛起涟漪",
                "usedRange": {"startSeconds": 0, "endSeconds": 4.5}
                """));

        assertThat(summary.shotCount()).isEqualTo(1);
        assertThat(summary.firstOrder()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateShotIds() {
        String json = """
                {
                  "schemaVersion": 1,
                  "title": "测试",
                  "shots": [
                    %s,
                    %s
                  ]
                }
                """.formatted(shot("shot-1", 1), shot("shot-1", 2));

        assertThatThrownBy(() -> validator.validate(json))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(AgentErrorCode.STORYBOARD_INVALID));
    }

    @Test
    void rejectsMissingFirstFramePrompt() {
        String json = storyboard("""
                "firstFramePrompt": "",
                "videoPrompt": "动作",
                "usedRange": {"startSeconds": 0, "endSeconds": 4.5}
                """);

        assertThatThrownBy(() -> validator.validate(json))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsUsedRangeBeyondShotDuration() {
        String json = storyboard("""
                "firstFramePrompt": "首帧",
                "videoPrompt": "动作",
                "usedRange": {"startSeconds": 0, "endSeconds": 6}
                """);

        assertThatThrownBy(() -> validator.validate(json))
                .isInstanceOf(BusinessException.class);
    }

    private String storyboard(String extraFields) {
        return """
                {
                  "schemaVersion": 1,
                  "title": "测试短片",
                  "aspectRatio": "16:9",
                  "language": "zh-CN",
                  "style": "写实",
                  "shots": [
                    {
                      "id": "shot-1",
                      "order": 1,
                      "sceneId": "scene-1",
                      "durationSeconds": 5,
                      "visual": "橘猫在门口",
                      "shotSize": "MEDIUM",
                      "camera": "STATIC",
                      "dialogue": "",
                      "voiceOver": "",
                      "sound": "",
                      "references": [],
                      "firstFrame": null,
                      "videoCandidates": [],
                      "selectedVideo": null,
                      "status": "DRAFT",
                      %s
                    }
                  ]
                }
                """.formatted(extraFields);
    }

    private String shot(String id, int order) {
        return """
                {
                  "id": "%s",
                  "order": %d,
                  "durationSeconds": 5,
                  "visual": "画面",
                  "firstFramePrompt": "首帧",
                  "videoPrompt": "动作",
                  "shotSize": "MEDIUM",
                  "camera": "STATIC",
                  "status": "DRAFT"
                }
                """.formatted(id, order);
    }
}
